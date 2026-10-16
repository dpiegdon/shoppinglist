package org.p23q.shoppinglist.core.api

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/**
 * What one account's API client reports about its requests, beyond the exception each call throws.
 * Implemented per account by [org.p23q.shoppinglist.core.account.AccountSessions], so a 401 or a
 * 426 from one server marks that account and no other.
 *
 * Called while the request is in flight: implementations must not block for long.
 */
interface ApiEvents {
    /**
     * A request that carried a bearer token was refused with 401: [sentToken], the token that
     * request carried, is dead. It need not be the account's token any more by now.
     */
    fun onUnauthorized(sentToken: String) {}

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

class ApiErrorsConfig {
    var json: Json = AppJson
    var events: ApiEvents = ApiEvents.NONE
}

/**
 * Turns a non-2xx answer into the typed exception its Wire Contract error envelope describes, and
 * tells [ApiErrorsConfig.events] what the answer says about the account.
 */
val ApiErrors = createClientPlugin("ApiErrors", ::ApiErrorsConfig) {
    val json = pluginConfig.json
    val events = pluginConfig.events
    on(Send) { request ->
        val call = proceed(request)
        val response = call.response
        if (response.status.isSuccess()) {
            val path = call.request.url.encodedPath
            if (UNCHECKED_PATH_SUFFIXES.none { path.endsWith(it) }) events.onAccepted()
            return@on call
        }

        val body = response.bodyAsChannel().readRemaining().readString()
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
        val code = envelope?.error ?: "unknown_error"
        val message = envelope?.message ?: response.status.description
        val httpCode = response.status.value

        if (httpCode == 401) {
            // A 401 on a request that actually carried a bearer token means "your token was
            // rejected" -> force re-login. A 401 with no Authorization header is an ordinary
            // login/register credential failure (no session to invalidate), so it must NOT trigger
            // a forced logout — it's surfaced to the caller as UnauthorizedException as usual.
            call.request.headers[HttpHeaders.Authorization]?.let { header ->
                events.onUnauthorized(header.removePrefix("Bearer "))
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
            version = envelope?.version,
        )
    }
}

/** Exempt from the protocol check, so a 2xx there says nothing about this build. */
private val UNCHECKED_PATH_SUFFIXES = setOf("/app-version")
