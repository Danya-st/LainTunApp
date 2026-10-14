package com.v2rayez.app.data.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Открываем во внешнем браузере только https-страницы оплаты ЮKassa
 * (confirmation_url из документации: https://yoomoney.ru/checkout/payments/v2/contract?orderId=...).
 * Защита на случай подмены/ошибки ответа API.
 */
object PaymentUrlPolicy {
    private val DOMAINS = listOf("yoomoney.ru", "yookassa.ru")

    fun isAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && DOMAINS.any { parsed.host == it || parsed.host.endsWith(".$it") }
    }
}
