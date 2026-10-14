package com.v2rayez.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.BuildConfig
import com.v2rayez.app.data.api.ApiResult
import com.v2rayez.app.data.api.Pkce
import com.v2rayez.app.data.api.Session
import com.v2rayez.app.data.api.SessionManager
import com.v2rayez.app.data.api.PaymentUrlPolicy
import com.v2rayez.app.data.api.Plan
import com.v2rayez.app.data.api.StatusResponse
import com.v2rayez.app.data.api.VpnApiRepository
import com.v2rayez.app.data.service.V2RayVpnService
import com.v2rayez.app.data.service.VpnStateHolder
import com.v2rayez.app.data.service.VpnStatus
import com.v2rayez.app.data.vps.SubscriptionError
import com.v2rayez.app.data.vps.SubscriptionFetcher
import com.v2rayez.app.data.vps.SubscriptionResult
import com.v2rayez.app.data.vps.VpsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface Access {
    data object Unknown : Access
    data class Active(val expiryTime: Long) : Access
    data class Expired(val expiryTime: Long) : Access
}

private fun Access.expiryOrZero(): Long = when (this) {
    is Access.Active -> expiryTime
    is Access.Expired -> expiryTime
    Access.Unknown -> 0L
}

sealed interface AuthState {
    data object Checking : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val login: String?) : AuthState
}

data class MainUiState(
    val auth: AuthState = AuthState.Checking,
    val loading: Boolean = true,          // первая загрузка статуса
    val access: Access = Access.Unknown,
    val vpnStatus: VpnStatus = VpnStatus.DISCONNECTED,
    val selectedPlan: Plan = Plan.M1,
    val showPlans: Boolean = false,       // ручное раскрытие тарифов при активной подписке
    val busy: Boolean = false,            // подготовка подключения / создание платежа
    val awaitingPayment: Boolean = false, // ждём, пока webhook продлит подписку
    val info: String? = null,
    val error: String? = null
)

sealed interface UiEvent {
    /** UI вызывает VpnService.prepare(); при успехе — vm.startVpnService(). */
    data object RequestVpnPermission : UiEvent
    /** UI открывает страницу оплаты в Custom Tab. */
    data class OpenPayment(val url: String) : UiEvent
    /** UI открывает страницу входа Яндекса в Custom Tab; результат придёт deep link'ом myvpn://auth. */
    data class OpenAuth(val url: String) : UiEvent
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val app: Application,
    private val store: VpsStore,
    private val sessions: SessionManager,
    private val api: VpnApiRepository,
    private val fetcher: SubscriptionFetcher,
    private val vpnState: VpnStateHolder
) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _ui.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var refreshJob: Job? = null
    private var pollJob: Job? = null
    private var expiryJob: Job? = null
    private var expiryBeforePayment = 0L

    init {
        viewModelScope.launch { vpnState.status.collect { s -> _ui.update { it.copy(vpnStatus = s) } } }
        viewModelScope.launch {
            vpnState.error.collect { e -> if (e != null) _ui.update { it.copy(error = e) } }
        }
    }

    // ───────────────────────── статус / регистрация ─────────────────────────

    /** Single-flight: LaunchedEffect и ON_RESUME могут вызвать его одновременно. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { loadAccess() }
    }

    fun onResume() {
        if (_ui.value.awaitingPayment) pollPayment() else refresh()
    }

    private suspend fun loadAccess() {
        val session = sessions.get()
        if (session == null || session.expiresAt <= System.currentTimeMillis()) {
            _ui.update { it.copy(loading = false, auth = AuthState.SignedOut, access = Access.Unknown, error = null, info = null) }
            return
        }
        _ui.update {
            it.copy(loading = it.access == Access.Unknown, auth = AuthState.SignedIn(session.login), error = null, info = null)
        }
        renewSessionIfNeeded(session)

        var status = api.status()
        if (status is ApiResult.Unauthorized) return onSessionRejected()

        // Клиента нет (404) либо ссылка подписки не сохранена (например, прошлый запуск прервался
        // между register и записью в DataStore). /register идемпотентен: существующему клиенту
        // вернёт его ссылку, ничего не создавая.
        if (status is ApiResult.NotFound || store.getUrl() == null) {
            when (val reg = api.register()) {
                is ApiResult.Ok -> {
                    reg.value.subLink?.let { store.saveUrl(it) }
                    if (status is ApiResult.NotFound) status = api.status()
                }
                is ApiResult.Unauthorized -> return onSessionRejected()
                else -> return fail(reg.toMessage())
            }
        }

        val s = status
        if (s !is ApiResult.Ok) return fail(s.toMessage())
        if (store.getUrl() == null) return fail("Сервер не вернул ссылку подписки")
        applyAccess(s.value)
    }

    private fun applyAccess(s: StatusResponse) {
        val access = if (s.isActive) Access.Active(s.expiryTime) else Access.Expired(s.expiryTime)
        _ui.update { it.copy(loading = false, access = access, error = null) }

        // Подписка кончилась, а туннель ещё поднят — отключаем (сервер всё равно перестанет пропускать).
        if (access is Access.Expired && _ui.value.vpnStatus != VpnStatus.DISCONNECTED) {
            sendToService(V2RayVpnService.ACTION_DISCONNECT)
        }
        scheduleExpiryCheck(access)
    }

    /** Пока приложение открыто: в момент истечения срока перепроверяем статус. */
    private fun scheduleExpiryCheck(access: Access) {
        expiryJob?.cancel()
        if (access !is Access.Active) return
        expiryJob = viewModelScope.launch {
            delay((access.expiryTime - System.currentTimeMillis() + 2_000).coerceAtLeast(0))
            refresh()
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(loading = false, error = message) }
    }

    // ───────────────────────── вход через Яндекс ─────────────────────────

    fun signIn() {
        if (BuildConfig.YANDEX_CLIENT_ID.isBlank()) return fail("Вход через Яндекс не настроен при сборке (YANDEX_CLIENT_ID)")
        viewModelScope.launch {
            val verifier = Pkce.newVerifier()
            val state = Pkce.newState()
            store.savePendingAuth(state, verifier)
            _ui.update { it.copy(error = null) }
            _events.send(UiEvent.OpenAuth(Pkce.authorizeUrl(BuildConfig.YANDEX_CLIENT_ID, Pkce.challenge(verifier), state)))
        }
    }

    fun onAuthOpenFailed() = fail("Не удалось открыть страницу входа: нет браузера")

    /** Deep link myvpn://auth?code=…&state=… (из MainActivity). */
    fun onAuthRedirect(uri: Uri) {
        if (uri.scheme != "myvpn" || uri.host != "auth") return
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val denied = uri.getQueryParameter("error") != null
        viewModelScope.launch {
            val pending = store.takePendingAuth()
            if (pending == null || state != pending.state) return@launch fail("Вход не подтверждён. Попробуйте ещё раз.")
            if (denied || code.isNullOrEmpty()) return@launch fail("Вход через Яндекс отменён")

            _ui.update { it.copy(loading = true, error = null) }
            when (val r = api.loginYandex(code, pending.verifier)) {
                is ApiResult.Ok -> {
                    val token = r.value.token
                    if (!r.value.success || token == null) return@launch fail("Сервер не вернул токен")
                    // Ссылка подписки и кэш конфига — от прошлого аккаунта/устройства: берём заново (/register идемпотентен).
                    store.clearAccountData()
                    sessions.save(Session(token, r.value.expiresAt, r.value.login))
                    refresh()
                }
                is ApiResult.Unauthorized -> fail("Яндекс не принял вход. Попробуйте ещё раз.")
                else -> fail(r.toMessage())
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            if (_ui.value.vpnStatus != VpnStatus.DISCONNECTED) sendToService(V2RayVpnService.ACTION_DISCONNECT)
            expiryJob?.cancel(); pollJob?.cancel()
            sessions.clear()
            store.clearAccountData()
            _ui.update { MainUiState(loading = false, auth = AuthState.SignedOut, vpnStatus = it.vpnStatus) }
        }
    }

    private suspend fun onSessionRejected() {
        sessions.clear()
        _ui.update {
            it.copy(loading = false, auth = AuthState.SignedOut, access = Access.Unknown, busy = false,
                error = "Сессия истекла. Войдите снова.")
        }
    }

    /** Скользящее продление: токен живёт 90 дней, при остатке < 45 дней берём новый (без обращения к Яндексу). */
    private suspend fun renewSessionIfNeeded(s: Session) {
        if (s.expiresAt - System.currentTimeMillis() > REFRESH_WHEN_LEFT_MS) return
        val r = api.refresh()
        if (r is ApiResult.Ok) {
            val token = r.value.token ?: return
            sessions.save(Session(token, r.value.expiresAt, r.value.login ?: s.login))
        }
    }

    // ───────────────────────── подключение ─────────────────────────

    /** Большая круглая кнопка. */
    fun onToggle() {
        val s = _ui.value
        when (s.vpnStatus) {
            VpnStatus.DISCONNECTED -> if (s.access is Access.Active && !s.busy) connect()
            VpnStatus.CONNECTING, VpnStatus.CONNECTED -> sendToService(V2RayVpnService.ACTION_DISCONNECT)
        }
    }

    private fun connect() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            vpnState.clearError()
            try {
                if (prepareConfig()) _events.send(UiEvent.RequestVpnPermission)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Скачивает подписку (сеть → Dispatchers.IO внутри fetcher), парсит VLESS+Reality и кладёт в DataStore.
     * @return true, если есть конфиг для подключения (свежий или кэш).
     */
    private suspend fun prepareConfig(): Boolean {
        val url = store.getUrl()
        if (url == null) {
            fail("Ссылка подписки не найдена. Нажмите «Обновить».")
            return false
        }

        var result = fetcher.fetch(url)

        // Ссылка могла устареть (админ пересоздал клиента): спрашиваем у сервера актуальную и пробуем ещё раз.
        val err = (result as? SubscriptionResult.Error)?.error
        if (err != null && err !is SubscriptionError.Network && err !is SubscriptionError.InvalidUrl) {
            val fresh = (api.register() as? ApiResult.Ok)?.value?.subLink
            if (fresh != null && fresh != url) {
                store.saveUrl(fresh)
                result = fetcher.fetch(fresh)
            }
        }

        return when (result) {
            is SubscriptionResult.Success -> {
                store.saveConfig(result.config)
                true
            }
            is SubscriptionResult.Error ->
                if (store.getConfig() != null) true // подписка недоступна — подключаемся по кэшу прошлого успеха
                else { fail(result.error.toMessage()); false }
        }
    }

    /** Вызывается UI после успешного VpnService.prepare(). */
    fun startVpnService() = sendToService(V2RayVpnService.ACTION_CONNECT)

    fun onPermissionDenied() = fail("Разрешение на VPN не выдано")

    private fun sendToService(action: String) {
        val intent = Intent(app, V2RayVpnService::class.java).setAction(action)
        ContextCompat.startForegroundService(app, intent)
    }

    // ───────────────────────── оплата ─────────────────────────

    fun selectPlan(plan: Plan) = _ui.update { it.copy(selectedPlan = plan) }

    fun toggleShowPlans() = _ui.update { it.copy(showPlans = !it.showPlans) }

    /** Кнопка «Продлить». */
    fun renew() {
        val s = _ui.value
        if (s.busy || s.awaitingPayment) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, info = null) }
            when (val r = api.createPayment(s.selectedPlan)) {
                is ApiResult.Ok -> {
                    val url = r.value.paymentUrl
                    if (url != null && PaymentUrlPolicy.isAllowed(url)) {
                        expiryBeforePayment = s.access.expiryOrZero()
                        _ui.update { it.copy(awaitingPayment = true) }
                        _events.send(UiEvent.OpenPayment(url))
                    } else {
                        fail("Сервер вернул некорректную ссылку на оплату")
                    }
                }
                is ApiResult.Unauthorized -> onSessionRejected()
                else -> fail(r.toMessage())
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun onPaymentOpenFailed() {
        _ui.update { it.copy(awaitingPayment = false) }
        fail("Не удалось открыть страницу оплаты: нет браузера")
    }

    /**
     * После возврата из Custom Tab опрашиваем /status, пока webhook ЮKassa не продлит срок.
     * Признак оплаты — рост expiryTime (у активной подписки isActive и так true).
     */
    private fun pollPayment() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            _ui.update { it.copy(info = "Проверяем оплату…") }
            repeat(POLL_ATTEMPTS) {
                val r = api.status()
                if (r is ApiResult.Ok && r.value.expiryTime > expiryBeforePayment) {
                    applyAccess(r.value)
                    _ui.update { it.copy(awaitingPayment = false, showPlans = false, info = "Подписка продлена") }
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
            _ui.update {
                it.copy(
                    awaitingPayment = false,
                    info = "Оплата пока не подтверждена. Если вы уже оплатили — подождите минуту и нажмите «Обновить»."
                )
            }
        }
    }

    // ───────────────────────── тексты ошибок ─────────────────────────

    private fun ApiResult<*>.toMessage(): String = when (this) {
        is ApiResult.Network -> "Нет связи с сервером. Проверьте интернет и повторите."
        ApiResult.Forbidden -> "Сервер отклонил запрос. Обновите приложение."
        ApiResult.Unauthorized -> "Сессия истекла. Войдите снова."
        ApiResult.NotFound -> "Подписка не найдена на сервере. Нажмите «Обновить»."
        is ApiResult.Http -> "Ошибка сервера (код $code)"
        is ApiResult.Ok<*> -> "Неизвестная ошибка"
    }

    private fun SubscriptionError.toMessage(): String = when (this) {
        SubscriptionError.InvalidConfigFormat ->
            "Сервер подписки вернул не конфиг (похоже на заглушку провайдера). Попробуйте другую сеть."
        SubscriptionError.NoRealityConfig -> "В подписке нет VLESS+Reality (tcp) конфигурации"
        SubscriptionError.InvalidUrl -> "Некорректная ссылка подписки"
        is SubscriptionError.Http -> "Сервер подписки ответил ошибкой: HTTP $code"
        is SubscriptionError.Network -> "Сервер подписки недоступен: ${message ?: "неизвестная ошибка"}"
    }

    private companion object {
        const val REFRESH_WHEN_LEFT_MS = 45L * 24 * 60 * 60 * 1000
        const val POLL_ATTEMPTS = 20
        const val POLL_INTERVAL_MS = 3_000L
    }
}
