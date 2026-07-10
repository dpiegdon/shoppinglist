package org.p23q.shoppinglist.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import javax.inject.Inject

data class OverviewUiState(
    val lists: List<ListEntity> = emptyList(),
    val isCreateDialogOpen: Boolean = false,
    val newListName: String = "",
    val sync: SyncState = SyncState(),
    /** The list holding a quarantined row, so the "needs attention" banner can open it (T-47). */
    val attentionListId: String? = null,
    /** True while a user-initiated pull-to-refresh sync is running, for the spinner (T-36). */
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val listsRepo: ListsRepo,
    private val itemsRepo: ItemsRepo,
    private val sessionState: SessionState,
    private val syncer: Syncer,
    syncStatus: SyncStatus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            listsRepo.activeLists().collect { lists -> _uiState.update { it.copy(lists = lists) } }
        }
        viewModelScope.launch {
            syncStatus.state.collect { sync ->
                // Resolve which list the attention banner should open only when something is blocked.
                val attentionListId = if (sync.blockedCount > 0) itemsRepo.firstBlockedItem()?.listId else null
                _uiState.update { it.copy(sync = sync, attentionListId = attentionListId) }
            }
        }
    }

    fun openCreateDialog() = _uiState.update { it.copy(isCreateDialogOpen = true, newListName = "") }

    fun dismissCreateDialog() = _uiState.update { it.copy(isCreateDialogOpen = false) }

    fun onNewListNameChange(value: String) = _uiState.update { it.copy(newListName = value) }

    /** Returns the launched Job, or null if the name was blank (dialog stays open, no-op). */
    fun createList(): Job? {
        val name = _uiState.value.newListName.trim()
        if (name.isBlank()) return null
        return viewModelScope.launch {
            listsRepo.createList(name)
            _uiState.update { it.copy(isCreateDialogOpen = false, newListName = "") }
        }
    }

    /** Notes: tapping a list card persists it as the one to reopen on next login/launch. */
    fun openList(listId: String) {
        sessionState.lastOpenedListId = listId
    }

    /** Manual pull-to-refresh: an immediate foreground sync with a visible spinner (T-36). */
    fun refresh(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isRefreshing = true) }
        try {
            syncer.syncNow(emptyList())
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }
}
