package com.v2rayez.app.data.vps

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.v2rayez.app.data.api.PendingAuth
import com.v2rayez.app.data.api.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vpsDataStore by preferencesDataStore(name = "vps_settings")

/**
 * DataStore: deviceId (UUIDv4, создаётся при первом запуске и больше не меняется),
 * subscriptionUrl (ссылка, выданная backend) и кэш последнего валидного VpsConfig.
 *
 * deviceId теперь нужен только для HMAC-подписи запросов; личность пользователя — Яндекс ID (сессия).
 * Выход стирает сессию и данные аккаунта (ссылка, конфиг), но не deviceId.
 */
@Singleton
class VpsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyDeviceId = stringPreferencesKey("device_id")
    private val keyUrl = stringPreferencesKey("subscription_url")
    private val keyConfig = stringPreferencesKey("vps_config")
    private val keySession = stringPreferencesKey("session")
    private val keyPendingAuth = stringPreferencesKey("pending_auth")
    private val json = Json { ignoreUnknownKeys = true }

    /** Атомарно (внутри транзакции edit): два параллельных вызова не создадут два разных id. */
    suspend fun getOrCreateDeviceId(): String {
        context.vpsDataStore.data.first()[keyDeviceId]?.let { return it }
        var result = ""
        context.vpsDataStore.edit { prefs ->
            result = prefs[keyDeviceId] ?: UUID.randomUUID().toString().also { prefs[keyDeviceId] = it }
        }
        return result
    }

    suspend fun getUrl(): String? = context.vpsDataStore.data.first()[keyUrl]

    suspend fun saveUrl(url: String) {
        context.vpsDataStore.edit { it[keyUrl] = url }
    }

    suspend fun saveConfig(config: VpsConfig) {
        context.vpsDataStore.edit { it[keyConfig] = json.encodeToString(VpsConfig.serializer(), config) }
    }

    /** Читается из V2RayVpnService при подключении. */
    suspend fun getConfig(): VpsConfig? =
        context.vpsDataStore.data.first()[keyConfig]
            ?.let { runCatching { json.decodeFromString(VpsConfig.serializer(), it) }.getOrNull() }

    // ── сессия Яндекс ID ──
    suspend fun getSession(): Session? =
        context.vpsDataStore.data.first()[keySession]
            ?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }

    suspend fun saveSession(s: Session) {
        context.vpsDataStore.edit { it[keySession] = json.encodeToString(Session.serializer(), s) }
    }

    suspend fun clearSession() {
        context.vpsDataStore.edit { it.remove(keySession) }
    }

    suspend fun savePendingAuth(state: String, verifier: String) {
        context.vpsDataStore.edit { it[keyPendingAuth] = "$state:$verifier" }
    }

    /** Читает и сразу удаляет: redirect можно обработать только один раз. */
    suspend fun takePendingAuth(): PendingAuth? {
        var raw: String? = null
        context.vpsDataStore.edit { prefs -> raw = prefs[keyPendingAuth]; prefs.remove(keyPendingAuth) }
        val parts = raw?.split(":") ?: return null
        return if (parts.size == 2) PendingAuth(parts[0], parts[1]) else null
    }

    /** Ссылка подписки и кэш конфига принадлежат конкретному аккаунту — при смене/выходе стираем. */
    suspend fun clearAccountData() {
        context.vpsDataStore.edit { it.remove(keyUrl); it.remove(keyConfig) }
    }
}
