package org.p23q.shoppinglist.data.sync

/**
 * The single sync operation a screen needs for manual pull-to-refresh (T-36): a seam over
 * [SyncEngine] so ViewModels depend on just this, and screen tests can supply a trivial fake instead
 * of constructing the whole engine (which needs the API client, DataStore, and Room).
 */
fun interface Syncer {
    suspend fun syncNow(fullLists: List<String>): SyncResult
}
