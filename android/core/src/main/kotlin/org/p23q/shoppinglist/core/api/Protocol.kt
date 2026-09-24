package org.p23q.shoppinglist.core.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The client/server protocol version (T-240).
 *
 * Every request carries [PROTOCOL_HEADER]; a server whose own version is higher answers
 * `426 client_outdated` before it authenticates, reads the database or writes an audit record, so
 * a build too old to read the current data is turned away rather than left to misread it.
 *
 * The same integer lives in `web/src/api/protocol.ts` and in the server's `protocol.py`; the web
 * suite's protocolVersion test reads all three and asserts they are equal.
 */
const val PROTOCOL_VERSION = 3

/**
 * The oldest server protocol this build still works against. A server is asked for its protocol
 * (`GET /app-version`) before the first login or registration there, and refused below this; a
 * server whose answer has no `protocol` at all predates T-240 and is refused too.
 *
 * A feature that needs a newer server is gated per account by its stored `serverProtocol`, never
 * assumed. Raise this only in a major release, and record it in the changelog in the "Protocol
 * version" section of docs/wire-contract.md.
 */
const val MIN_SERVER_PROTOCOL = 3

const val PROTOCOL_HEADER = "X-Client-Protocol"

/**
 * Adds the protocol header to every request, login and register included — the server checks the
 * protocol before authentication, so a request without it is refused whoever sends it.
 *
 * An interceptor rather than a Retrofit `@Headers` annotation on each method: one place that
 * cannot be forgotten when an endpoint is added, and it covers the app-version request too (that
 * one endpoint stays reachable while outdated, but sending the header there costs nothing).
 */
class ProtocolInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString()).build(),
    )
}
