package com.v2rayez.app

import com.v2rayez.app.data.api.AppSigner
import com.v2rayez.app.data.api.PaymentUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSignerTest {

    /**
     * Вектор получен независимо тремя способами (JVM javax.crypto, Node backend computeAppSignature,
     * `openssl dgst -sha256 -hmac`) и совпал — значит, формат Android ↔ backend согласован.
     */
    @Test
    fun matchesBackendVector() {
        val sig = AppSigner.sign(
            deviceId = "3f2b8c1e-7a4d-4e9b-8c3a-1d2e3f4a5b6c",
            timestamp = "1800000000000",
            secret = "test-secret-key"
        )
        assertEquals("1604b7df168bba30eea7be2471048fc9d90bff2346024aaff4524af14f5a3846", sig)
    }

    @Test
    fun paymentUrlPolicy() {
        assertTrue(PaymentUrlPolicy.isAllowed("https://yoomoney.ru/checkout/payments/v2/contract?orderId=28646d50-000f-5000-a000-1fe4f81e5e88"))
        assertTrue(PaymentUrlPolicy.isAllowed("https://pay.yookassa.ru/x"))
        assertFalse(PaymentUrlPolicy.isAllowed("http://yoomoney.ru/x"))               // не https
        assertFalse(PaymentUrlPolicy.isAllowed("https://yoomoney.ru.evil.com/"))      // чужой домен
        assertFalse(PaymentUrlPolicy.isAllowed("https://evilyoomoney.ru/"))           // суффикс без точки
        assertFalse(PaymentUrlPolicy.isAllowed("https://evil.com/?x=yoomoney.ru"))
        assertFalse(PaymentUrlPolicy.isAllowed("https://payform.ru/"))                // старый провайдер
        assertFalse(PaymentUrlPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(PaymentUrlPolicy.isAllowed(""))
    }
}
