package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.data.sync.SyncStatus
import javax.inject.Inject

/** Sync health for the top bar's status dot, on every screen (T-178). */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(syncStatus: SyncStatus) : ViewModel() {
    val state: StateFlow<SyncState> = syncStatus.state
}
