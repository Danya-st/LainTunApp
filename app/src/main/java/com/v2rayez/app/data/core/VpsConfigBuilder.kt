package com.v2rayez.app.data.core

import com.v2rayez.app.data.vps.VpsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Заменяет ConfigBuilder.kt (40 КБ: VMess/Trojan/SS/WG/Tor/WARP/fronting/Iran-bypass).
 * Только VLESS + Reality, только полный TUN (fd отдаётся в libv2ray через startLoop).
 *
 * ВАЖНО: в этом форке libv2ray.aar сам читает TUN fd → нужен inbound `protocol: "tun"`.
 * Локальный SOCKS 10808 здесь НЕ нужен (и вреден: любое приложение на устройстве могло бы
 * подключиться к 127.0.0.1:10808 и узнать exit-IP).
 */
object VpsConfigBuilder {

    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS = "dns-out"

    /** Должен совпадать с VpnService.Builder.setMtu(). */
    const val TUN_MTU = 1400

    /**
     * "AsIs", а не "IPIfNonMatch": geoip:private содержит 198.18.0.0/15 — это тот же пул,
     * из которого FakeDNS раздаёт fake-IP. При IPIfNonMatch домен после sniffing резолвится
     * обратно в fake-IP, попадает под geoip:private и уходит в direct (утечка + не работает).
     * Хотите всё-таки IPIfNonMatch — замените geoip:private на явные CIDR (10/8, 172.16/12,
     * 192.168/16, 169.254/16, fc00::/7, fe80::/10).
     */
    private const val ROUTING_DOMAIN_STRATEGY = "AsIs"

    /** Chrome/HTTP3 через TUN-прокси часто даёт "подключено, но интернета нет" — блокируем QUIC, браузер уходит на TCP. */
    private const val BLOCK_QUIC = true

    private val json = Json { prettyPrint = false }

    fun build(cfg: VpsConfig): String {
        val obj = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            // Нужно для queryAllOutboundTrafficStats (V2RayCore.queryTrafficStats)
            putJsonObject("stats") {}
            putJsonObject("policy") {
                putJsonObject("system") {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }
            put("dns", dns())
            put("fakedns", fakedns())
            put("inbounds", inbounds())
            put("outbounds", outbounds(cfg))
            put("routing", routing())
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun dns(): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            add("fakedns")   // сначала FakeDNS: приложения получают fake-IP, реальный резолв — на VPS
            add("1.1.1.1")   // fallback; запрос сам идёт через outbound proxy (правило "всё остальное")
        }
        put("queryStrategy", "UseIP")   // IPv6 включён в TUN (addRoute("::", 0)) → AAAA тоже обслуживаем
    }

    private fun fakedns(): JsonArray = buildJsonArray {
        addJsonObject {
            put("ipPool", "198.18.0.0/15")
            put("poolSize", 65535)
        }
        addJsonObject {
            put("ipPool", "fc00::/18")
            put("poolSize", 65535)
        }
    }

    private fun inbounds(): JsonArray = buildJsonArray {
        addJsonObject {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("port", 0)
            putJsonObject("settings") {
                put("name", "xray0")
                put("mtu", TUN_MTU)
                put("stack", "gvisor")
            }
            putJsonObject("sniffing") {
                put("enabled", true)
                putJsonArray("destOverride") {
                    add("http"); add("tls"); add("quic"); add("fakedns")
                }
            }
        }
    }

    private fun outbounds(cfg: VpsConfig): JsonArray = buildJsonArray {
        // Первый outbound = дефолтный: всё, что не поймали правила, уходит в proxy.
        addJsonObject {
            put("tag", TAG_PROXY)
            put("protocol", "vless")
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", cfg.address)
                        put("port", cfg.port)
                        putJsonArray("users") {
                            addJsonObject {
                                put("id", cfg.uuid)
                                put("encryption", "none")
                                if (cfg.flow.isNotBlank()) put("flow", cfg.flow)
                            }
                        }
                    }
                }
            }
            putJsonObject("streamSettings") {
                put("network", "tcp")
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", cfg.serverName)
                    put("fingerprint", cfg.fingerprint)
                    put("publicKey", cfg.publicKey)
                    put("shortId", cfg.shortId)
                    put("spiderX", cfg.spiderX)
                }
            }
        }
        addJsonObject {
            put("tag", TAG_DIRECT)
            put("protocol", "freedom")
            putJsonObject("settings") { put("domainStrategy", "UseIP") }
        }
        addJsonObject {
            put("tag", TAG_BLOCK)
            put("protocol", "blackhole")
            putJsonObject("settings") {
                putJsonObject("response") { put("type", "http") }
            }
        }
        // Без этого outbound'а dns-блок и FakeDNS не получают запросы приложений из TUN.
        addJsonObject {
            put("tag", TAG_DNS)
            put("protocol", "dns")
        }
    }

    private fun routing(): JsonObject = buildJsonObject {
        put("domainStrategy", ROUTING_DOMAIN_STRATEGY)
        putJsonArray("rules") {
            // 0) DNS приложений (порт 53) → встроенный DNS-модуль (FakeDNS). Должно стоять ПЕРВЫМ.
            addJsonObject {
                put("type", "field")
                put("port", "53")
                put("outboundTag", TAG_DNS)
            }
            // 1) Локальные сети напрямую (приложение исключено из VPN, цикла нет)
            addJsonObject {
                put("type", "field")
                putJsonArray("ip") { add("geoip:private") }
                put("outboundTag", TAG_DIRECT)
            }
            // 2) Реклама → block. Требует geosite.dat в assets (см. build.gradle: ignoreAssetsPatterns убран)
            addJsonObject {
                put("type", "field")
                putJsonArray("domain") { add("geosite:category-ads-all") }
                put("outboundTag", TAG_BLOCK)
            }
            if (BLOCK_QUIC) {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("protocol") { add("quic") }
                    put("outboundTag", TAG_BLOCK)
                }
            }
            // 3) Всё остальное → дефолтный (первый) outbound = proxy. Явное правило не нужно.
        }
    }
}
