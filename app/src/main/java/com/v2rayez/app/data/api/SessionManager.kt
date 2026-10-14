package com.v2rayez.app.data.api

import com.v2rayez.app.data.vps.VpsStore
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** Кэш сессии в памяти поверх DataStore. [token] зовётся только из потока OkHttp (как DeviceIdProvider). */
@Singleton
class SessionManager @Inject constructor(private val store: VpsStore) {
    @Volatile private var cached: Session? = null
    @Volatile private var loaded = false

    fun token(): String? {
        if (!loaded) { cached = runBlocking { store.getSession() }; loaded = true }
        return cached?.token
    }

    suspend fun get(): Session? {
        if (!loaded) { cached = store.getSession(); loaded = true }
        return cached
    }

    suspend fun save(s: Session) { store.saveSession(s); cached = s; loaded = true }

    suspend fun clear() { store.clearSession(); cached = null; loaded = true }
}
