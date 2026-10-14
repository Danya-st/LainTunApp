package com.v2rayez.app.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED }

/** Процесс-широкое состояние. Единственный писатель — [V2RayVpnService]. */
@Singleton
class VpnStateHolder @Inject constructor() {

    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    /** Последняя ошибка подключения (null = нет). Сбрасывается при новой попытке. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setConnecting() { _error.value = null; _status.value = VpnStatus.CONNECTING }
    fun setConnected() { _status.value = VpnStatus.CONNECTED }
    fun setDisconnected() { _status.value = VpnStatus.DISCONNECTED }
    fun setError(message: String) { _error.value = message; _status.value = VpnStatus.DISCONNECTED }
    fun clearError() { _error.value = null }
}
