package org.p23q.shoppinglist.data.sync

/** Requests a debounced background sync after a local edit — A2's repos call this on every mutation. */
fun interface SyncTrigger {
    fun scheduleAfterEdit()
}
