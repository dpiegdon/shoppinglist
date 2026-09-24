package org.p23q.shoppinglist.core.api

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Parses the Wire Contract's error envelope on non-2xx responses and throws a typed exception. */
class ErrorInterceptor @Inject constructor(
    private val json: Json,
    private val sessionEvents: SessionEvents,
    // Defaulted only so the many tests that build an interceptor by hand and care about neither
    // the protocol nor the state do not all have to name it; Hilt always injects the singleton.
    private val protocolState: ProtocolState = ProtocolState(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response

        val body = response.body?.string().orEmpty()
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
        val code = envelope?.error ?: "unknown_error"
        val message = envelope?.message ?: response.message
        val httpCode = response.code

        response.close()
        if (httpCode == 401) {
            // A 401 on a request that actually carried a bearer token means "your token was
            // rejected" -> force re-login. A 401 with no Authorization header is an ordinary
            // login/register credential failure (no session to invalidate), so it must NOT trigger
            // a forced logout — it's surfaced to the caller as UnauthorizedException as usual.
            if (chain.request().header("Authorization") != null) {
                sessionEvents.notifyForcedLogout()
            }
            throw UnauthorizedException(message)
        }
        // 426: this build's protocol is older than the server's (T-240). It says nothing about the
        // session and nothing about the row that happened to be in flight, so it raises the
        // app-wide state and otherwise goes on to be an ordinary ApiException — no forced logout,
        // no quarantine. One choke point here covers foreground screens and the background worker
        // alike, exactly as the 401 rule above does.
        if (httpCode == 426) {
            protocolState.notifyClientOutdated()
        }
        throw ApiException(
            code,
            message,
            httpCode,
            rowId = envelope?.rowId,
            field = envelope?.field,
            accountId = envelope?.accountId,
        )
    }
}
