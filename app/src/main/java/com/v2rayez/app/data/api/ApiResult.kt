package com.v2rayez.app.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** HTTP 404: для /status — «клиент не найден». */
    data object NotFound : ApiResult<Nothing>
    /** HTTP 403: неверная подпись / устаревший timestamp. */
    data object Forbidden : ApiResult<Nothing>
    /** HTTP 401: нет/просрочен/неверен токен сессии — нужен вход. */
    data object Unauthorized : ApiResult<Nothing>
    data class Http(val code: Int) : ApiResult<Nothing>
    data class Network(val message: String?) : ApiResult<Nothing>
}

internal suspend fun <T> safeCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val r = block()
    when {
        r.isSuccessful -> r.body()?.let { ApiResult.Ok(it) } ?: ApiResult.Http(r.code())
        r.code() == 404 -> ApiResult.NotFound
        r.code() == 403 -> ApiResult.Forbidden
        r.code() == 401 -> ApiResult.Unauthorized
        else -> ApiResult.Http(r.code())
    }
} catch (e: CancellationException) {
    throw e // не глотаем отмену корутины
} catch (e: IOException) {
    ApiResult.Network(e.message)
} catch (e: SerializationException) {
    ApiResult.Http(-1) // сервер вернул не то, что мы ожидаем (например, HTML от прокси)
}
