package com.v2rayez.app.data.api

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth 2.0 Authorization Code + PKCE (RFC 7636) для Яндекс ID. Без зависимостей от Android. */
object Pkce {
    const val REDIRECT_URI = "myvpn://auth"   // = Callback URI в приложении Яндекса и YANDEX_REDIRECT_URI backend
    private const val AUTHORIZE_URL = "https://oauth.yandex.ru/authorize"
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val rnd = SecureRandom()

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(rnd::nextBytes).let(b64::encodeToString)

    fun newVerifier(): String = randomToken(32)   // 43 символа
    fun newState(): String = randomToken(16)

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(clientId: String, challenge: String, state: String): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$AUTHORIZE_URL?response_type=code" +
            "&client_id=${e(clientId)}" +
            "&redirect_uri=${e(REDIRECT_URI)}" +
            "&code_challenge=${e(challenge)}&code_challenge_method=S256" +
            "&state=${e(state)}"
    }
}
