package com.v2rayez.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Response<T> (а не просто T): 404 у /status — штатный ответ «клиента нет», а не исключение.
 * Заголовки X-Device-Id / X-Timestamp / X-App-Signature добавляет [HmacInterceptor], Authorization — [AuthInterceptor].
 */
interface ApiService {
    @POST("api/v1/auth/yandex")
    suspend fun loginYandex(@Body body: AuthRequest): Response<AuthResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(): Response<AuthResponse>

    // Пользователь определяется по Bearer-токену, а не по телу/query.
    @POST("api/v1/register")
    suspend fun register(): Response<RegisterResponse>

    @GET("api/v1/status")
    suspend fun status(): Response<StatusResponse>

    @POST("api/v1/create-payment")
    suspend fun createPayment(@Body body: PaymentRequest): Response<PaymentResponse>
}
