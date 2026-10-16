package org.p23q.shoppinglist.core.api

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath

/** The account's token as it is now, read for each request; null when it has none. Must not block. */
fun interface TokenProvider {
    fun currentToken(): String?
}

class BearerAuthConfig {
    var tokens: TokenProvider = TokenProvider { null }
}

/**
 * Adds `Authorization: Bearer <token>` to every request when a token is present — except
 * credential-exchange endpoints (/login, /register), which must never carry the current session
 * token (mirrors the web fix, T-89 ab7e059): a wrong-password 401 there must not look like a
 * revoked session and trip [ApiErrors]' forced-logout path. /logout deliberately keeps its
 * token — the server needs it to know which session to revoke.
 */
val BearerAuth = createClientPlugin("BearerAuth", ::BearerAuthConfig) {
    val tokens = pluginConfig.tokens
    onRequest { request, _ ->
        val token = tokens.currentToken() ?: return@onRequest
        val path = request.url.encodedPath
        if (SKIPPED_PATH_SUFFIXES.none { path.endsWith(it) }) {
            request.headers.append(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

/** Credential exchanges — never carry the current session token. Path-suffix matched so a server
 *  mounted under a path prefix (e.g. https://host/shopping/api/v1/login) is still covered. */
private val SKIPPED_PATH_SUFFIXES = setOf("/login", "/register")
