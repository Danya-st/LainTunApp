package com.v2rayez.app.data.vps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionFetcher @Inject constructor() {

    // Дефолтный TLS и системное хранилище сертификатов (Let's Encrypt). Никаких кастомных TrustManager.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Вся сеть и парсинг выполняются в Dispatchers.IO, на main thread ничего не блокируется. */
    suspend fun fetch(url: String): SubscriptionResult = withContext(Dispatchers.IO) {
        val httpUrl = url.trim().toHttpUrlOrNull()
        if (httpUrl == null || !httpUrl.isHttps) {
            return@withContext SubscriptionResult.Error(SubscriptionError.InvalidUrl)
        }
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "MyVPS-Client/1.0")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext SubscriptionResult.Error(SubscriptionError.Http(resp.code))
                }
                // Лимит 1 МБ: заглушка/мусор не должны съедать память.
                val body = resp.peekBody(MAX_BODY_BYTES).string()
                SubscriptionParser.parse(body)
            }
        } catch (e: IOException) {
            SubscriptionResult.Error(SubscriptionError.Network(e.message))
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1_048_576L
    }
}
