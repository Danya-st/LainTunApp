package com.v2rayez.app.data.vps

import java.net.URLDecoder
import java.util.Base64

/**
 * Чистая логика без android.* — покрывается обычными JVM unit-тестами.
 * java.util.Base64 доступен с API 26 (minSdk проекта = 26).
 */
object SubscriptionParser {

    // Base64 (стандартный + URL-safe алфавит). Пробелы/переводы строк вырезаются заранее.
    private val base64Regex = Regex("^[A-Za-z0-9+/_-]+={0,2}$")
    private val shortIdRegex = Regex("^[0-9a-fA-F]{0,16}$")

    fun parse(rawBody: String): SubscriptionResult {
        val trimmed = rawBody.trim()

        // Plain-text подписка (3x-ui с выключенным шифрованием): сразу список ссылок.
        // HTML-заглушка с "vless://" не начинается, так что защита от DPI не страдает.
        val text: String = if (trimmed.startsWith("vless://", ignoreCase = true)) {
            trimmed
        } else {
            // 1) СНАЧАЛА проверяем, что это вообще Base64. HTML содержит < > " ' = пробелы — regex отсекает.
            val compact = trimmed.filterNot { it.isWhitespace() }
            if (compact.length < 16 || !base64Regex.matches(compact)) {
                return SubscriptionResult.Error(SubscriptionError.InvalidConfigFormat)
            }
            // 2) Безопасное декодирование — исключения не выходят наружу.
            val decoded = try {
                val decoder = if (compact.contains('-') || compact.contains('_')) {
                    Base64.getUrlDecoder()
                } else {
                    Base64.getDecoder()
                }
                String(decoder.decode(compact), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return SubscriptionResult.Error(SubscriptionError.InvalidConfigFormat)
            }
            // Бинарный мусор после декодирования (случайно "валидный" Base64).
            if (decoded.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
                return SubscriptionResult.Error(SubscriptionError.InvalidConfigFormat)
            }
            decoded
        }

        // 3) Только vless:// + security=reality (+ type=tcp)
        val config = text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull(::parseVlessReality)
            .firstOrNull()
            ?: return SubscriptionResult.Error(SubscriptionError.NoRealityConfig)

        return SubscriptionResult.Success(config)
    }

    internal fun parseVlessReality(link: String): VpsConfig? {
        // vless://UUID@host:port?query#fragment — разбираем вручную (java.net.URI падает на emoji в #fragment)
        val body = link.substring("vless://".length).substringBefore('#')
        val authority = body.substringBefore('?').substringBefore('/')
        val at = authority.lastIndexOf('@')
        if (at <= 0) return null

        val uuid = authority.substring(0, at)
        val (host, port) = splitHostPort(authority.substring(at + 1)) ?: return null
        val q = parseQuery(body.substringAfter('?', ""))

        if (!q["security"].equals("reality", ignoreCase = true)) return null
        // Билдер конфига рассчитан только на raw TCP. xhttp/grpc/ws с Reality тут не поддерживаются.
        if (!(q["type"] ?: "tcp").equals("tcp", ignoreCase = true)) return null

        val pbk = q["pbk"].orEmpty()
        val sni = q["sni"].orEmpty()
        val sid = q["sid"].orEmpty()
        if (uuid.isBlank() || host.isBlank() || port !in 1..65535) return null
        if (sni.isBlank() || !isValidRealityKey(pbk) || !shortIdRegex.matches(sid)) return null

        return VpsConfig(
            address = host,
            port = port,
            uuid = uuid,
            publicKey = pbk,
            shortId = sid,
            serverName = sni,
            flow = q["flow"].orEmpty(),
            fingerprint = q["fp"]?.takeIf { it.isNotBlank() } ?: "chrome",
            spiderX = q["spx"].orEmpty()
        )
    }

    private fun splitHostPort(s: String): Pair<String, Int>? {
        if (s.startsWith("[")) {                       // IPv6: [::1]:443
            val end = s.indexOf(']')
            if (end < 0) return null
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return s.substring(1, end) to port
        }
        val i = s.lastIndexOf(':')
        if (i <= 0) return null
        val port = s.substring(i + 1).toIntOrNull() ?: return null
        return s.substring(0, i) to port
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .filter { it.contains('=') }
            .associate {
                val k = it.substringBefore('=')
                // '+' в pbk/sid не бывает, но URLDecoder превратил бы его в пробел — экранируем.
                val v = it.substringAfter('=').replace("+", "%2B")
                k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault("")
            }

    /** Reality public key = 32 байта X25519 в base64url. Ловим опечатки до запуска ядра. */
    private fun isValidRealityKey(value: String): Boolean = runCatching {
        Base64.getUrlDecoder().decode(value.trim().replace('+', '-').replace('/', '_')).size == 32
    }.getOrDefault(false)
}
