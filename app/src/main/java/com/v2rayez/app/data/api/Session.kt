package com.v2rayez.app.data.api

import kotlinx.serialization.Serializable

/** Сессия, выданная backend после входа через Яндекс: подписанный токен (stateless) + срок, мс. */
@Serializable
data class Session(val token: String, val expiresAt: Long, val login: String? = null)

/** state и code_verifier незаконченного входа: переживают смерть процесса, пока пользователь в браузере. */
data class PendingAuth(val state: String, val verifier: String)
