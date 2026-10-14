package com.v2rayez.app

import com.v2rayez.app.data.vps.SubscriptionError
import com.v2rayez.app.data.vps.SubscriptionParser
import com.v2rayez.app.data.vps.SubscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionParserTest {

    private val pbk = "A".repeat(43) // 32 байта в base64url
    private val reality =
        "vless://11111111-2222-3333-4444-555555555555@vps.example.com:443" +
            "?type=tcp&security=reality&pbk=$pbk&sid=ab12&sni=www.microsoft.com" +
            "&fp=chrome&flow=xtls-rprx-vision&spx=%2F#My%20%F0%9F%87%A9%F0%9F%87%AA"

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun htmlStubIsInvalidFormat_notCrash() {
        val html = "<html><head><title>Доступ ограничен</title></head><body>Blocked</body></html>"
        val r = SubscriptionParser.parse(html)
        assertEquals(SubscriptionResult.Error(SubscriptionError.InvalidConfigFormat), r)
    }

    @Test fun validBase64WithReality() {
        val r = SubscriptionParser.parse(b64(reality + "\n")) as SubscriptionResult.Success
        with(r.config) {
            assertEquals("vps.example.com", address)
            assertEquals(443, port)
            assertEquals("ab12", shortId)
            assertEquals("www.microsoft.com", serverName)
            assertEquals("xtls-rprx-vision", flow)
            assertEquals("chrome", fingerprint)
            assertEquals("/", spiderX)
        }
    }

    @Test fun nonRealityLinksAreSkipped() {
        val vmess = "vmess://eyJ2IjoiMiJ9"
        val tls = "vless://11111111-2222-3333-4444-555555555555@h.example:443?security=tls&sni=h.example"
        val r = SubscriptionParser.parse(b64("$vmess\n$tls\n"))
        assertEquals(SubscriptionResult.Error(SubscriptionError.NoRealityConfig), r)
    }

    @Test fun picksRealityAmongMixed() {
        val tls = "vless://11111111-2222-3333-4444-555555555555@h.example:443?security=tls&sni=h.example"
        assertTrue(SubscriptionParser.parse(b64("$tls\n$reality\n")) is SubscriptionResult.Success)
    }

    @Test fun plainTextSubscription() {
        assertTrue(SubscriptionParser.parse(reality) is SubscriptionResult.Success)
    }

    @Test fun badPublicKeyRejected() {
        val bad = reality.replace(pbk, "short")
        assertEquals(
            SubscriptionResult.Error(SubscriptionError.NoRealityConfig),
            SubscriptionParser.parse(b64(bad))
        )
    }

    @Test fun nonTcpTransportRejected() {
        val xhttp = reality.replace("type=tcp", "type=xhttp")
        assertEquals(
            SubscriptionResult.Error(SubscriptionError.NoRealityConfig),
            SubscriptionParser.parse(b64(xhttp))
        )
    }

    @Test fun ipv6Host() {
        val v6 = reality.replace("vps.example.com:443", "[2001:db8::1]:8443")
        val r = SubscriptionParser.parse(b64(v6)) as SubscriptionResult.Success
        assertEquals("2001:db8::1", r.config.address)
        assertEquals(8443, r.config.port)
    }
}
