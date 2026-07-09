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
import org.p23q.shoppinglist.data.repo.ListsRepo
import javax.inject.Inject

data class OverviewUiState(
    val lists: List<ListEntity> = emptyList(),
    val isCreateDialogOpen: Boolean = false,
    val newListName: String = "",
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val listsRepo: ListsRepo,
    private val sessionState: SessionState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            listsRepo.activeLists().collect { lists -> _uiState.update { it.copy(lists = lists) } }
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
}
