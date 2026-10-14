package com.v2rayez.app.data.api

import okhttp3.Interceptor
import okhttp3.Response

/** Добавляет `Authorization: Bearer <session>`; без токена запрос уходит как есть и получает 401 → экран входа. */
class AuthInterceptor(private val token: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.encodedPath.endsWith("/auth/yandex")) return chain.proceed(req)
        val t = token() ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $t").build())
    }
}
