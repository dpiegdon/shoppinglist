package org.p23q.shoppinglist.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Observable sync health, updated by [SyncEngine] on every run and read by the sync-status surfaces.
 * One [SyncState] per account in [accounts], and in [state] the aggregate the status bar shows: the
 * worst of the accounts, so one account that cannot sync is never hidden behind another that can.
 *
 * - in progress while any account is syncing;
 * - the oldest last successful sync, and none while any account has never synced;
 * - the first account's error, in account order, if any has one;
 * - pending and blocked rows summed.
 *
 * With one account the aggregate is that account's state, field for field.
 */
@Singleton
class SyncStatus @Inject constructor() {
    private val lock = Any()
    private val perAccount = LinkedHashMap<String, SyncState>()
    private val _accounts = MutableStateFlow<Map<String, SyncState>>(emptyMap())
    private val _state = MutableStateFlow(SyncState())

    /** The aggregate over every account; see the class comment. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Each account's own state, by local account id. */
    val accounts: StateFlow<Map<String, SyncState>> = _accounts.asStateFlow()

    /** The recorder for one account's runs. */
    fun account(accountId: String): AccountSyncStatus = AccountSyncStatus(this, accountId)

    /** Forgets an account that was removed from the device. */
    fun remove(accountId: String) = synchronized(lock) {
        perAccount.remove(accountId)
        publish()
    }

    internal fun change(accountId: String, transform: (SyncState) -> SyncState) = synchronized(lock) {
        perAccount[accountId] = transform(perAccount[accountId] ?: SyncState())
        publish()
    }

    private fun publish() {
        val states = perAccount.values.toList()
        _accounts.value = LinkedHashMap(perAccount)
        _state.value = if (states.isEmpty()) {
            SyncState()
        } else {
            SyncState(
                inProgress = states.any { it.inProgress },
                lastSyncAt = if (states.any { it.lastSyncAt == null }) null else states.minOf { it.lastSyncAt!! },
                lastError = states.firstNotNullOfOrNull { it.lastError },
                pendingCount = states.sumOf { it.pendingCount },
                blockedCount = states.sumOf { it.blockedCount },
            )
        }
    }
}

/** One account's share of [SyncStatus]. */
class AccountSyncStatus internal constructor(private val status: SyncStatus, val accountId: String) {
    fun started(pending: Int, blocked: Int) = status.change(accountId) {
        it.copy(inProgress = true, pendingCount = pending, blockedCount = blocked)
    }

    /**
     * Sets the pending/blocked counts straight from the database, without a sync having run
     * (T-265). Every scheduled sync requires connectivity (SyncScheduler's network constraint), so
     * a cold start offline would otherwise show the initial zeros — no pending count, no "needs
     * attention" banner — until one finally runs. Leaves [SyncState.inProgress] and
     * [SyncState.lastError] untouched: this is not a sync attempt, so it has no verdict to report.
     */
    fun seed(pending: Int, blocked: Int) = status.change(accountId) {
        it.copy(pendingCount = pending, blockedCount = blocked)
    }

    fun succeeded(at: Long, pending: Int, blocked: Int) = status.change(accountId) {
        it.copy(inProgress = false, lastSyncAt = at, lastError = null, pendingCount = pending, blockedCount = blocked)
    }

    fun failed(error: String, pending: Int, blocked: Int) = status.change(accountId) {
        it.copy(inProgress = false, lastError = error, pendingCount = pending, blockedCount = blocked)
    }

    /**
     * Session expired mid-sync: clears the spinner but is *not* a loud error — the forced-logout
     * flow (T-31) drives re-auth, after which sync resumes.
     */
    fun stoppedUnauthorized(pending: Int, blocked: Int) = status.change(accountId) {
        it.copy(inProgress = false, pendingCount = pending, blockedCount = blocked)
    }

    /**
     * This app is too old for the server (T-240). Like [stoppedUnauthorized] this clears the
     * spinner without recording an error: the blocking update screen is what the user is looking
     * at, and a red "sync failed" behind it would only add noise to a state they cannot act on
     * from here. The pending and blocked counts are left exactly as they were — nothing about the
     * queue changed.
     */
    fun stoppedOutdated(pending: Int, blocked: Int) = status.change(accountId) {
        it.copy(inProgress = false, pendingCount = pending, blockedCount = blocked)
    }
}
