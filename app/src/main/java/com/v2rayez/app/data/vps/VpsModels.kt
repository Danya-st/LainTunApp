package com.v2rayez.app.data.vps

import kotlinx.serialization.Serializable

/** Единственный поддерживаемый профиль: VLESS + Reality (+ XTLS-Vision). */
@Serializable
data class VpsConfig(
    val address: String,
    val port: Int,
    val uuid: String,
    val publicKey: String,      // pbk
    val shortId: String,        // sid (может быть пустым)
    val serverName: String,     // sni
    val flow: String,           // xtls-rprx-vision (пусто = без flow)
    val fingerprint: String,    // fp
    val spiderX: String = ""    // spx (опционально)
)

sealed class SubscriptionError {
    /** Ответ не Base64 (HTML-заглушка провайдера/DPI) либо после декодирования получился мусор. */
    data object InvalidConfigFormat : SubscriptionError()

    /** Формат валиден, но нет ни одной подходящей vless:// ссылки с security=reality (type=tcp). */
    data object NoRealityConfig : SubscriptionError()

    data object InvalidUrl : SubscriptionError()
    data class Http(val code: Int) : SubscriptionError()
    data class Network(val message: String?) : SubscriptionError()
}

/** Собственный sealed-класс (не kotlin.Result), чтобы ViewModel делала exhaustive `when`. */
sealed class SubscriptionResult {
    data class Success(val config: VpsConfig) : SubscriptionResult()
    data class Error(val error: SubscriptionError) : SubscriptionResult()
}
