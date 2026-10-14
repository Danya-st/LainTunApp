package com.v2rayez.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Все сетевые вызовы — в Dispatchers.IO; ошибки возвращаются как [ApiResult], а не исключения. */
@Singleton
class VpnApiRepository @Inject constructor(private val api: ApiService) {
    suspend fun loginYandex(code: String, verifier: String): ApiResult<AuthResponse> = withContext(Dispatchers.IO) {
        safeCall { api.loginYandex(AuthRequest(code, verifier)) }
    }

    suspend fun refresh(): ApiResult<AuthResponse> = withContext(Dispatchers.IO) { safeCall { api.refresh() } }

    suspend fun register(): ApiResult<RegisterResponse> = withContext(Dispatchers.IO) { safeCall { api.register() } }

    suspend fun status(): ApiResult<StatusResponse> = withContext(Dispatchers.IO) { safeCall { api.status() } }

    suspend fun createPayment(plan: Plan): ApiResult<PaymentResponse> = withContext(Dispatchers.IO) {
        safeCall { api.createPayment(PaymentRequest(plan)) }
    }
}
