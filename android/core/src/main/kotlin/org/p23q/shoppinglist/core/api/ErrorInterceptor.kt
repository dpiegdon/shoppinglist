package org.p23q.shoppinglist.core.api

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response

/**
 * What one account's API client reports about its requests, beyond the exception each call throws.
 * Implemented per account by [org.p23q.shoppinglist.core.account.AccountSessions], so a 401 or a
 * 426 from one server marks that account and no other.
 *
 * Called on OkHttp's thread: implementations must not block for long and must not suspend.
 */
interface ApiEvents {
    /** A request that carried a bearer token was refused with 401: the token is dead. */
    fun onUnauthorized() {}

    /** The server refused this build's protocol version (426, T-240). */
    fun onOutdated() {}

    /**
     * A protocol-checked request succeeded, so the server accepts this build. Not called for
     * `/app-version`, which the server answers whatever the protocol.
     */
    fun onAccepted() {}

    companion object {
        val NONE: ApiEvents = object : ApiEvents {}
    }
}

/** Parses the Wire Contract's error envelope on non-2xx responses and throws a typed exception. */
class ErrorInterceptor(
    private val json: Json,
    private val events: ApiEvents = ApiEvents.NONE,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) {
            val path = chain.request().url.encodedPath
            if (UNCHECKED_PATH_SUFFIXES.none { path.endsWith(it) }) events.onAccepted()
            return response
        }

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
                events.onUnauthorized()
            }
            throw UnauthorizedException(message)
        }
        // 426: this build's protocol is older than the server's (T-240). It says nothing about the
        // session and nothing about the row that happened to be in flight, so it marks the account
        // and otherwise goes on to be an ordinary ApiException — no forced logout, no quarantine.
        // One choke point here covers foreground screens and the background worker alike, exactly
        // as the 401 rule above does.
        if (httpCode == 426) {
            events.onOutdated()
        }
        throw ApiException(
            code,
            message,
            httpCode,
            rowId = envelope?.rowId,
            field = envelope?.field,
            accountId = envelope?.accountId,
            protocol = envelope?.protocol,
        )
    }

    private companion object {
        /** Exempt from the protocol check, so a 2xx there says nothing about this build. */
        val UNCHECKED_PATH_SUFFIXES = setOf("/app-version")
    }
}
