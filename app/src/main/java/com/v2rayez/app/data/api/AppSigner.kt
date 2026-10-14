package com.v2rayez.app.data.api

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * X-App-Signature = hex_lowercase( HMAC_SHA256(key = secret, message = deviceId + timestamp) ).
 * Без зависимостей от Android — тестируется обычным JUnit. Формат сверен с backend (appAuth.js).
 */
object AppSigner {
    fun sign(deviceId: String, timestamp: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal((deviceId + timestamp).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
