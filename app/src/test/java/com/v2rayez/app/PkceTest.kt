package com.v2rayez.app

import com.v2rayez.app.data.api.Pkce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    /** Вектор из RFC 7636, приложение B. */
    @Test
    fun challengeMatchesRfcVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun verifierIsValidAndRandom() {
        val v = Pkce.newVerifier()
        assertTrue(Regex("^[A-Za-z0-9\\-._~]{43,128}$").matches(v))
        assertTrue(v != Pkce.newVerifier())
    }

    @Test
    fun authorizeUrlHasPkceParams() {
        val url = Pkce.authorizeUrl("cid", "chal", "st")
        assertTrue(url.startsWith("https://oauth.yandex.ru/authorize?response_type=code&client_id=cid"))
        assertTrue("code_challenge=chal&code_challenge_method=S256" in url)
        assertTrue("redirect_uri=myvpn%3A%2F%2Fauth" in url)
        assertTrue("state=st" in url)
    }
}
