package org.p23q.shoppinglist.data


/**
 * Whether the app currently has a started (visible) activity — set by ShoppingListApp's
 * ProcessLifecycleOwner observer. Used to suppress collaborator-change notifications while the
 * user is already looking at live data (T-65). @Volatile: written on the main thread, read from
 * WorkManager's background thread.
 */
class AppForegroundState {
    @Volatile
    var isForeground: Boolean = false
}
