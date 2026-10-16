package org.p23q.shoppinglist.core.sync

import kotlinx.coroutines.CancellationException
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.db.AccountEntity

/**
 * An invite waiting for an account on this phone (T-319), as `/invites/pending` lists it.
 * [accountId] is the local id of the account it was sent to; [expiresAt] is epoch milliseconds.
 */
data class PendingInvite(
    val accountId: String,
    val id: String,
    val listName: String,
    val invitedByInitials: String,
    val expiresAt: Long,
)

/**
 * How an invite check ended (T-319), shown in Settings → Diagnostics. [NOTHING_NEW] means every
 * invite the servers listed had been seen on this phone before; the rest are the notifier's gates,
 * in the order it checks them, or [POSTED].
 */
enum class InviteCheckOutcome { NOTHING_NEW, FOREGROUND, INVITES_OFF, NO_PERMISSION, POSTED }

/**
 * Seam between [InviteChecker] and the Android notification machinery, as
 * [CollaboratorChangeNotifier] is for the sync engine. It gets every invite the servers listed in
 * one check, seen or not: which ones are new to this phone, and whether to post, is its job.
 */
fun interface InviteNotifier {
    suspend fun notifyPendingInvites(invites: List<PendingInvite>)
}

/**
 * Asks `/invites/pending` of each account whose last sync succeeded (T-319), once per call, and
 * hands every answer, together, to [notifier]. Run after a background sync, so invites are
 * noticed without the app polling any more often than it syncs. An account that is signed out,
 * outdated or without a token is not asked, nor one whose sync failed; a request that fails leaves
 * that account's invites out of this check. With no account answering, [notifier] is not called.
 */
class InviteChecker(
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
    private val syncStatus: SyncStatus,
    private val notifier: InviteNotifier,
) {
    suspend fun check() {
        val askable = registry.load().filter(::canAsk)
        var answered = false
        val invites = askable.flatMap { account ->
            try {
                val listed = sessions.get(account.id).api.pendingInvites().invites
                answered = true
                listed.map { PendingInvite(account.id, it.id, it.listName, it.invitedByInitials, it.expiresAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, a server without the endpoint, a session that just ended, or an account
                // removed while its request was out: nothing to notify of for it this time.
                emptyList()
            }
        }
        if (answered) notifier.notifyPendingInvites(invites)
    }

    private fun canAsk(account: AccountEntity): Boolean {
        if (!account.isServer || !account.signedIn || account.outdated || !sessions.hasToken(account.id)) return false
        val state = syncStatus.accounts.value[account.id] ?: return false
        return state.lastSyncAt != null && state.lastError == null
    }
}
