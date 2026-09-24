package org.p23q.shoppinglist.core.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

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

const val PROTOCOL_HEADER = "X-Client-Protocol"

/**
 * Adds the protocol header to every request, login and register included — the server checks the
 * protocol before authentication, so a request without it is refused whoever sends it.
 *
 * An interceptor rather than a Retrofit `@Headers` annotation on each method: one place that
 * cannot be forgotten when an endpoint is added, and it covers the app-version request too (that
 * one endpoint stays reachable while outdated, but sending the header there costs nothing).
 */
class ProtocolInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString()).build(),
    )
}

/**
 * App-wide "this app is too old for its server" state (T-240).
 *
 * Deliberately a state and not an event like [SessionEvents.forcedLogout]: it does not go away by
 * itself — every request will keep being refused until the app is updated — so both the UI (which
 * blocks the whole screen on it) and [org.p23q.shoppinglist.core.sync.SyncEngine] (which stops
 * rather than hammer a server that will never accept it) read the current value rather than having
 * to have been listening at the right moment.
 *
 * Being outdated is NOT being logged out and NOT a bad row: nothing is cleared, quarantined or
 * dropped anywhere on this path. A successful install restarts the app, which clears the state;
 * if it is ever cleared without an update, the next 426 raises it again.
 */
@Singleton
class ProtocolState @Inject constructor() {
    private val _updateRequired = MutableStateFlow(false)
    val updateRequired: StateFlow<Boolean> = _updateRequired.asStateFlow()

    /** Non-suspending — safe to call from any thread, including an OkHttp interceptor. */
    fun notifyClientOutdated() {
        _updateRequired.value = true
    }
}
