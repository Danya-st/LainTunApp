package com.v2rayez.app.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.v2rayez.app.MainActivity
import com.v2rayez.app.R
import com.v2rayez.app.data.core.V2RayCore
import com.v2rayez.app.data.core.VpsConfigBuilder
import com.v2rayez.app.data.vps.VpsConfig
import com.v2rayez.app.data.vps.VpsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Минимальный VpnService: VPN-интерфейс (весь трафик устройства) + libv2ray через V2RayCore.
 *
 * Жизненный цикл:
 *  ACTION_CONNECT → startForeground → [IO] читаем VpsConfig → резолвим IP сервера (до TUN!) →
 *  establish() → core.startLoop(json, tunFd) → CONNECTED.
 *  ACTION_DISCONNECT / onRevoke / ошибка → core.stopLoop() → закрыть TUN → stopSelf().
 *
 * Все JNI-вызовы (V2RayCore.startLoop/stopLoop) и сеть идут в Dispatchers.IO (scope сервиса).
 */
@AndroidEntryPoint
class V2RayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.v2rayez.app.CONNECT"
        const val ACTION_DISCONNECT = "com.v2rayez.app.DISCONNECT"
        private const val TAG = "V2RayVpnService"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_INTERVAL_MS = 2_000L
        private const val WATCHDOG_MAX_MISSES = 3
        private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }

    @Inject lateinit var core: V2RayCore
    @Inject lateinit var store: VpsStore
    @Inject lateinit var state: VpnStateHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Сериализует connect / teardown, чтобы два жизненных цикла никогда не пересекались. */
    private val lifecycleMutex = Mutex()
    private val generation = AtomicInteger(0)
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var tun: ParcelFileDescriptor? = null

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Сервис запускается через startForegroundService(): startForeground() обязателен
        // в течение ~5 с на ЛЮБОМ пути (в т.ч. DISCONNECT), иначе ForegroundServiceDidNotStartInTimeException.
        promoteToForeground(if (intent?.action == ACTION_DISCONNECT) "Отключение…" else "Подключение…")

        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            return START_NOT_STICKY
        }

        // ACTION_CONNECT либо перезапуск системой (intent == null) → подключаемся к сохранённому конфигу.
        connectJob?.cancel()
        val gen = generation.incrementAndGet()
        state.setConnecting()
        connectJob = scope.launch { connect(gen) }
        return START_STICKY
    }

    override fun onRevoke() {
        // Пользователь отозвал VPN-разрешение / включил другой VPN.
        stopTunnel()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        connectJob?.cancel()
        watchdogJob?.cancel()
        val leftoverTun = tun
        tun = null
        // Остановку нативного ядра делаем вне scope сервиса: scope.cancel() ниже убил бы её.
        // Если teardown уже отработал (обычный disconnect) — ничего не трогаем, чтобы не убить
        // ядро нового экземпляра сервиса при быстром reconnect.
        if (leftoverTun != null || core.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { core.stopLoop() }
                runCatching { leftoverTun?.close() }
            }
        }
        if (state.status.value != VpnStatus.DISCONNECTED) state.setDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    // ───────────────────────── connect / stop ─────────────────────────

    private suspend fun connect(gen: Int) {
        lifecycleMutex.withLock {
            if (gen != generation.get()) return
            teardown() // на случай «хвостов» от прошлой сессии

            val cfg = store.getConfig()
                ?: return fail("Конфигурация не найдена. Сохраните ссылку подписки заново.")

            // Резолвим домен сервера ДО создания TUN (иначе DNS пойдёт в ещё не готовый туннель,
            // а FakeDNS вернул бы fake-IP для адреса самого VPS).
            val resolved = resolveServerAddress(cfg)
                ?: return fail("Не удалось определить IP сервера ${cfg.address}")
            if (gen != generation.get()) return

            val pfd = establishTun() ?: return fail("Не удалось создать VPN-интерфейс")
            tun = pfd
            if (gen != generation.get()) { teardown(); return }

            // JNI: V2RayCore.startLoop сам переключается на Dispatchers.IO.
            val ok = core.startLoop(VpsConfigBuilder.build(resolved), pfd.fd)
            if (gen != generation.get()) { teardown(); return }
            if (!ok || !core.isRunning) {
                return fail(core.lastStartError() ?: "Ядро Xray не запустилось")
            }

            state.setConnected()
            updateNotification("Подключено")
            startWatchdog(gen)
        }
    }

    private fun stopTunnel() {
        generation.incrementAndGet()
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            lifecycleMutex.withLock {
                teardown()
                state.setDisconnected()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    /** Вызывать только под [lifecycleMutex]. */
    private suspend fun fail(message: String) {
        Log.e(TAG, message)
        teardown()
        state.setError(message)
        stopForegroundCompat()
        stopSelf()
    }

    /** Порядок важен: сначала ядро (читает fd), потом закрываем TUN. Вызывать под [lifecycleMutex]. */
    private suspend fun teardown() {
        watchdogJob?.cancel()
        watchdogJob = null
        runCatching { core.stopLoop() }.onFailure { Log.e(TAG, "stopLoop failed", it) }
        runCatching { tun?.close() }
        tun = null
    }

    /** Если ядро умерло молча (OOM/panic в Go) — не показываем «Подключено» вечно. */
    private fun startWatchdog(gen: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var misses = 0
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (core.isRunning) { misses = 0; continue }
                if (++misses >= WATCHDOG_MAX_MISSES) {
                    // Отдельная корутина: fail() вызывает teardown(), который отменяет watchdogJob.
                    scope.launch {
                        lifecycleMutex.withLock {
                            if (gen == generation.get()) fail("Ядро Xray остановилось")
                        }
                    }
                    return@launch
                }
            }
        }
    }

    // ───────────────────────── TUN ─────────────────────────

    private fun establishTun(): ParcelFileDescriptor? = runCatching {
        val builder = Builder()
            .setSession("VPS")
            .setMtu(VpsConfigBuilder.TUN_MTU)
            .addAddress("10.10.14.1", 30)
            .addAddress("fdfe:dcba:9876::1", 126)
            // Полный туннель: и IPv4, и IPv6 (иначе v6-трафик утекает мимо VPN)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // DNS-запросы приложений попадают в TUN → правило port 53 → dns-out → FakeDNS
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
            // КРИТИЧНО: свой трафик (сокеты Xray к VPS, direct) идёт мимо TUN → нет routing loop.
            // Если вызов бросит исключение — НЕ продолжаем (runCatching → null → fail()).
            .addDisallowedApplication(packageName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(false) // gVisor-стек ждёт non-blocking fd
            builder.setMetered(false)
        }
        builder.establish()
    }.onFailure { Log.e(TAG, "TUN establish failed", it) }.getOrNull()

    /** Reality подключается по IP; SNI (serverName) задаётся отдельно, поэтому подмена address безопасна. */
    private fun resolveServerAddress(cfg: VpsConfig): VpsConfig? {
        if (ipv4Regex.matches(cfg.address) || cfg.address.contains(':')) return cfg
        val all = runCatching { InetAddress.getAllByName(cfg.address) }.getOrNull() ?: return null
        val ip = (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress ?: return null
        return cfg.copy(address = ip)
    }

    // ───────────────────────── notification ─────────────────────────

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } catch (e: Exception) {
                    // Страховка: ROM/версия отклонила systemExempted → specialUse (объявлен в Manifest).
                    Log.w(TAG, "systemExempted rejected, falling back to specialUse", e)
                    startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.e(TAG, "startForeground failed", it) }
    }

    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val disconnect = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, V2RayVpnService::class.java).setAction(ACTION_DISCONNECT),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("VPS VPN")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Отключить", disconnect)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
