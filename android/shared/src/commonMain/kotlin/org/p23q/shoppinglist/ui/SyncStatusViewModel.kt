package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.p23q.shoppinglist.core.sync.SyncState
import org.p23q.shoppinglist.core.sync.SyncStatus

/** Sync health for the top bar's status dot, on every screen (T-178). */
class SyncStatusViewModel(syncStatus: SyncStatus) : ViewModel() {
    val state: StateFlow<SyncState> = syncStatus.state
}
