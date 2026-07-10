package org.p23q.shoppinglist.data.api

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide signal that the current session is no longer valid and the user must re-authenticate.
 *
 * Emitted from [ErrorInterceptor] when a request that carried a bearer token comes back 401 (the
 * server revoked/expired the token — e.g. another device called "revoke session", or the password
 * was changed elsewhere). A single choke point at the interceptor means every API path (foreground
 * screens AND the background [org.p23q.shoppinglist.data.sync.SyncWorker]) funnels through the same
 * detection; the root of the UI ([org.p23q.shoppinglist.ui.ShoppingListNavHost]) collects it and
 * routes to Login.
 *
 * replay = 0 (a late subscriber must not re-trigger a stale logout after we've already navigated
 * away), with a small buffer + DROP_OLDEST so [notifyForcedLogout] is non-suspending and safe to
 * call from OkHttp's blocking interceptor thread. If a 401 arrives while no collector is active
 * (app killed, background worker), the event is dropped — but the dead token is still present, so
 * the very next API interaction after relaunch 401s again and this time is observed. It self-heals.
 */
@Singleton
class SessionEvents @Inject constructor() {
    private val _forcedLogout = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val forcedLogout: SharedFlow<Unit> = _forcedLogout.asSharedFlow()

    /** Non-suspending — safe to call from any thread, including an OkHttp interceptor. */
    fun notifyForcedLogout() {
        _forcedLogout.tryEmit(Unit)
    }
}
