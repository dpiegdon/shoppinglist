package org.p23q.shoppinglist.core.sync

/**
 * The single sync operation a screen needs for manual pull-to-refresh (T-36): a seam over
 * [SyncEngine] so ViewModels depend on just this, and screen tests can supply a trivial fake instead
 * of constructing the whole engine (which needs the accounts, their API clients and Room).
 */
fun interface Syncer {
    /** Syncs every account; [fullLists] (server ids) of lists this phone already holds. */
    suspend fun syncNow(fullLists: List<String>): SyncResult

    /**
     * Syncs every account, asking [accountId]'s server for the full snapshot of the list it has just
     * joined ([serverListId]), which this phone does not hold yet and so cannot tell whose it is.
     * A fake that only counts syncs can leave this to [syncNow].
     */
    suspend fun syncJoined(accountId: String, serverListId: String): SyncResult = syncNow(listOf(serverListId))
}
