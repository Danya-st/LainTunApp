package com.v2rayez.app.data.api

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.abs

/**
 * Добавляет к КАЖДОМУ запросу X-Device-Id, X-Timestamp, X-App-Signature.
 *
 * Работает на потоке OkHttp (не на main), поэтому deviceIdProvider может блокироваться на DataStore.
 *
 * Сервер отвергает запросы с |разница времени| > 5 минут. Если часы телефона сильно уходят,
 * приложение иначе вечно получало бы 403. Поэтому при 403 берём время из заголовка `Date` ответа,
 * запоминаем смещение и один раз повторяем запрос.
 */
class HmacInterceptor(
    private val deviceId: () -> String,
    private val secret: String,
    private val now: () -> Long = System::currentTimeMillis
) : Interceptor {

    @Volatile private var offsetMs: Long = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        if (secret.isBlank()) {
            throw IOException("APP_HMAC_SECRET не задан при сборке (см. local.properties)")
        }
        val original = chain.request()
        var response = chain.proceed(sign(original))

        if (response.code == 403) {
            val serverTime = response.headers.getDate("Date")?.time
            if (serverTime != null && abs(serverTime - now()) > SKEW_THRESHOLD_MS) {
                offsetMs = serverTime - now()
                response.close()
                response = chain.proceed(sign(original)) // ровно одна повторная попытка
            }
        }
        return response
    }

    private fun sign(request: Request): Request {
        val id = deviceId()
        val timestamp = (now() + offsetMs).toString()
        return request.newBuilder()
            .header("X-Device-Id", id)
            .header("X-Timestamp", timestamp)
            .header("X-App-Signature", AppSigner.sign(id, timestamp, secret))
            .build()
    }

    private companion object {
        const val SKEW_THRESHOLD_MS = 60_000L
    }
}
