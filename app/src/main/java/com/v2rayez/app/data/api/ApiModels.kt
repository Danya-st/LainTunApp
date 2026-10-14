package com.v2rayez.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(val code: String, val codeVerifier: String)

@Serializable
data class AuthResponse(
    val success: Boolean = false,
    val token: String? = null,
    val expiresAt: Long = 0,
    val login: String? = null
)

@Serializable
data class RegisterResponse(
    val success: Boolean = false,
    val subLink: String? = null,
    val expiryTime: Long = 0
)

@Serializable
data class StatusResponse(
    val success: Boolean = false,
    val isActive: Boolean = false,
    val expiryTime: Long = 0
)

/**
 * Тарифы. Значения @SerialName — ровно те, что принимает backend (PLANS в config/plans.js).
 * Цены здесь только для отображения; реальную цену задаёт и проверяет сервер.
 */
@Serializable
enum class Plan(val priceRub: Int, val label: String) {
    @SerialName("1_month") M1(150, "1 месяц"),
    @SerialName("2_months") M2(280, "2 месяца"),
    @SerialName("3_months") M3(400, "3 месяца")
}

@Serializable
data class PaymentRequest(val plan: Plan)

@Serializable
data class PaymentResponse(
    val success: Boolean = false,
    val paymentUrl: String? = null
)
