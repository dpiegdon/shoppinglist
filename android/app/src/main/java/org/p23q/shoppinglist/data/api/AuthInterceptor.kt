package org.p23q.shoppinglist.data.api

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Synchronous token lookup — OkHttp interceptors run on a blocking call chain, not as suspend. */
fun interface TokenProvider {
    fun currentToken(): String?
}

/**
 * Injects `Authorization: Bearer <token>` on every request when a token is present — except
 * credential-exchange endpoints (/login, /register), which must never carry the current session
 * token (mirrors the web fix, T-89 ab7e059): a wrong-password 401 there must not look like a
 * revoked session and trip ErrorInterceptor's forced-logout path. /logout deliberately keeps its
 * token — the server needs it to know which session to revoke.
 */
class AuthInterceptor @Inject constructor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider.currentToken()
        val path = original.url.encodedPath
        val skipAuth = SKIPPED_PATH_SUFFIXES.any { path.endsWith(it) }
        val request = if (token != null && !skipAuth) {
            original.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    private companion object {
        /** Credential exchanges — never carry the current session token. Path-suffix matched so
         *  a server mounted under a path prefix (e.g. https://host/shopping/api/v1/login) is
         *  still covered. */
        val SKIPPED_PATH_SUFFIXES = setOf("/login", "/register")
    }
}
