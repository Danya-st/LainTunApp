package com.v2rayez.app.data.api

import com.v2rayez.app.data.vps.VpsStore
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый источник deviceId для заголовка и тела запроса (сервер требует их совпадения).
 * Значение неизменно, поэтому кэшируется; первое чтение блокирует только поток OkHttp/IO, не main.
 */
@Singleton
class DeviceIdProvider @Inject constructor(private val store: VpsStore) {
    @Volatile private var cached: String? = null

    fun get(): String = cached ?: runBlocking { store.getOrCreateDeviceId() }.also { cached = it }
}
