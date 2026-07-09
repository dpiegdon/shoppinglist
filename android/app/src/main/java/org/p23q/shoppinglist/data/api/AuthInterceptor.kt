package org.p23q.shoppinglist.data.api

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Synchronous token lookup — OkHttp interceptors run on a blocking call chain, not as suspend. */
fun interface TokenProvider {
    fun currentToken(): String?
}

/** Injects `Authorization: Bearer <token>` on every request when a token is present. */
class AuthInterceptor @Inject constructor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider.currentToken()
        val request = if (token != null) {
            original.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
