package org.p23q.shoppinglist.ui.registry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.ui.Routes
import java.text.Collator
import javax.inject.Inject

data class RegistryUiState(
    val query: String = "",
    val items: List<ItemEntity> = emptyList(),
    val undoItemId: String? = null,
    val undoItemName: String? = null,
)

/** Notes: "all items" browse/search across every status, reachable from the list's menu. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RegistryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemsRepo: ItemsRepo,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(RegistryUiState())
    val uiState: StateFlow<RegistryUiState> = _uiState.asStateFlow()

    private val query = MutableStateFlow("")

    /**
     * Case-insensitive name order (T-137).
     *
     * A Collator rather than an ORDER BY ... COLLATE NOCASE, because SQLite's NOCASE only
     * case-folds ASCII: in the eight non-English locales this app ships, anything accented or
     * non-Latin would fall back to code-point order and land after every plain-ASCII name —
     * "Apfel", "Butter", "Zucker", "Äpfel". SECONDARY strength is what makes it ignore case
     * while still telling accented letters apart.
     *
     * Held per instance, not shared: Collator is mutable and not thread-safe.
     */
    private val byName = compareBy(Collator.getInstance().apply { strength = Collator.SECONDARY }) {
        item: ItemEntity -> item.name.value
    }

    init {
        viewModelScope.launch {
            query.flatMapLatest { q -> itemsRepo.searchRegistry(listId, q) }
                // Sorted here rather than in the DAO so the ordering rule sits with the screen
                // that wants it; a registry is one list's items, so this is cheap.
                .collect { items -> _uiState.update { it.copy(items = items.sortedWith(byName)) } }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        query.value = value
    }

    /** Notes: delete tombstones with a snackbar undo, same pattern as the list view's checkOff. */
    fun deleteItem(itemId: String): Job = viewModelScope.launch {
        val item = itemsRepo.getById(itemId) ?: return@launch
        itemsRepo.delete(itemId)
        _uiState.update { it.copy(undoItemId = itemId, undoItemName = item.name.value) }
    }

    fun undoDelete(): Job = viewModelScope.launch {
        val itemId = _uiState.value.undoItemId ?: return@launch
        itemsRepo.restore(itemId)
        _uiState.update { it.copy(undoItemId = null, undoItemName = null) }
    }

    fun dismissUndo() = _uiState.update { it.copy(undoItemId = null, undoItemName = null) }
}
