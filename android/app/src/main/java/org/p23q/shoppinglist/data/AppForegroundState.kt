package org.p23q.shoppinglist.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app currently has a started (visible) activity — set by ShoppingListApp's
 * ProcessLifecycleOwner observer. Used to suppress collaborator-change notifications while the
 * user is already looking at live data (T-65). @Volatile: written on the main thread, read from
 * WorkManager's background thread.
 */
@Singleton
class AppForegroundState @Inject constructor() {
    @Volatile
    var isForeground: Boolean = false
}
