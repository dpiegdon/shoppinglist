package org.p23q.shoppinglist.data.api

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Parses the Wire Contract's error envelope on non-2xx responses and throws a typed exception. */
class ErrorInterceptor @Inject constructor(private val json: Json) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response

        val body = response.body?.string().orEmpty()
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
        val code = envelope?.error ?: "unknown_error"
        val message = envelope?.message ?: response.message

        response.close()
        if (response.code == 401) throw UnauthorizedException(message)
        throw ApiException(code, message, response.code)
    }
}
