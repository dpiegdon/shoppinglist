package org.p23q.shoppinglist.data.sync

/**
 * Requests background syncs. Repos call [scheduleAfterEdit] on every local mutation (debounced);
 * [scheduleImmediate] runs a sync as soon as possible — used on app foreground and right after a
 * successful login, so a freshly authenticated session pulls its data without waiting for the next
 * incidental trigger (otherwise the first screen sits empty until a manual refresh).
 */
interface SyncTrigger {
    fun scheduleAfterEdit()
    fun scheduleImmediate()
}
