package org.p23q.shoppinglist.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A snapshot of sync health for the UI (T-47). [lastSyncAt] is epoch-ms of the last *successful*
 * sync; [lastError] is the message of the last failure (cleared on the next success); [pendingCount]
 * is dirty rows still to push; [blockedCount] is rows the server quarantined with a 422 (T-32) and
 * that now need the user's attention.
 */
data class SyncState(
    val inProgress: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val blockedCount: Int = 0,
)

/**
 * App-wide observable sync health, updated by [SyncEngine] on every run and read by the sync-status
 * surfaces (the Overview status line here; the pull-to-refresh spinner in T-36). A single @Singleton
 * holder so there is one source of truth rather than one per screen.
 */
@Singleton
class SyncStatus @Inject constructor() {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun started(pending: Int, blocked: Int) = _state.update {
        it.copy(inProgress = true, pendingCount = pending, blockedCount = blocked)
    }

    fun succeeded(at: Long, pending: Int, blocked: Int) = _state.update {
        it.copy(inProgress = false, lastSyncAt = at, lastError = null, pendingCount = pending, blockedCount = blocked)
    }

    fun failed(error: String, pending: Int, blocked: Int) = _state.update {
        it.copy(inProgress = false, lastError = error, pendingCount = pending, blockedCount = blocked)
    }

    /**
     * Session expired mid-sync: clears the spinner but is *not* a loud error — the forced-logout
     * flow ([org.p23q.shoppinglist.data.api.SessionEvents], T-31) drives re-auth, after which sync
     * resumes.
     */
    fun stoppedUnauthorized(pending: Int, blocked: Int) = _state.update {
        it.copy(inProgress = false, pendingCount = pending, blockedCount = blocked)
    }
}
