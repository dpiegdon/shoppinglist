package org.p23q.shoppinglist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.p23q.shoppinglist.data.repo.ListsRepo
import javax.inject.Inject

/**
 * The current list's name, observed live, for the top-bar title on the list / registry / list-props
 * destinations (T-34). Those screens sit inside [AppDrawerScaffold]'s content lambda, so the name
 * their own ViewModels know can't be read at the scaffold call site; this tiny ViewModel — scoped to
 * the same nav back-stack entry — provides it to the scaffold's title, and updates on rename/sync.
 */
@HiltViewModel
class ListTitleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    listsRepo: ListsRepo,
) : ViewModel() {
    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    val name: StateFlow<String> = listsRepo.observeById(listId)
        .map { it?.name?.value ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * The list's kind, for the nav host to pick the right screen (T-154). Null until the row is
     * known: an expenses list rendered as a shopping list for one frame would flash the wrong
     * screen on every visit, so the caller waits rather than guessing.
     */
    val kind: StateFlow<String?> = listsRepo.observeById(listId)
        .map { it?.kind?.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
