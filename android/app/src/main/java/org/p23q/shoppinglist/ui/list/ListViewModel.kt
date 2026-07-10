package org.p23q.shoppinglist.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.repo.Price
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import javax.inject.Inject

/** A category header ([category] `null` = uncategorized, rendered last) plus its sorted items. */
data class ItemGroup(val category: String?, val items: List<ItemEntity>)

data class ListUiState(
    val listName: String = "",
    val groups: List<ItemGroup> = emptyList(),
    val showChecked: Boolean = false,
    val defaultCurrency: String? = null,
    /** Number of checked items in the list (regardless of the show-checked toggle) — drives the
     *  'Clear checked (N)' action's visibility and label (T-35). */
    val checkedCount: Int = 0,
    val undoItemId: String? = null,
    val undoItemName: String? = null,
    /** Ids just bulk-cleared to backlog, held so the undo snackbar can restore them (T-35). */
    val clearedCheckedIds: List<String> = emptyList(),
    /** Live sync health for the recency line (T-47). */
    val sync: SyncState = SyncState(),
    /** True while a user-initiated pull-to-refresh sync is running, for the spinner (T-36). */
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class ListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    private val syncer: Syncer,
    syncStatus: SyncStatus,
    sessionState: SessionState,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ListUiState(defaultCurrency = sessionState.defaultCurrency))
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    private var categoryOrder: List<String> = emptyList()
    private var todoItems: List<ItemEntity> = emptyList()
    private var checkedItems: List<ItemEntity> = emptyList()

    init {
        // Combine the list row with the todo + checked item streams (all observed live, so a rename,
        // a category-order change, or another device's sync updates without recreating the screen —
        // T-34). combine holds the first grouped emission until ALL three flows have emitted once, so
        // grouping is never computed from a half-loaded snapshot: without this, the item streams
        // (off Dispatchers.IO) could land before the list row and briefly group by an empty
        // category_order — an order flash on open, and a race that flaked the ordering test.
        viewModelScope.launch {
            combine(
                listsRepo.observeById(listId),
                itemsRepo.itemsForListByStatus(listId, Status.TODO),
                itemsRepo.itemsForListByStatus(listId, Status.CHECKED),
                ::Triple,
            ).collect { (list, todo, checked) ->
                categoryOrder = list?.let { listsRepo.decodeCategoryOrder(it.categoryOrder.value) } ?: emptyList()
                todoItems = todo
                checkedItems = checked
                _uiState.update { it.copy(listName = list?.name?.value ?: "") }
                regroup()
            }
        }
        viewModelScope.launch {
            syncStatus.state.collect { sync -> _uiState.update { it.copy(sync = sync) } }
        }
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

    fun toggleShowChecked() {
        _uiState.update { it.copy(showChecked = !it.showChecked) }
        regroup()
    }

    /** Notes: tapping a todo row's body checks it off and arms a brief undo snackbar. */
    fun checkOff(itemId: String): Job = viewModelScope.launch {
        val item = itemsRepo.getById(itemId) ?: return@launch
        itemsRepo.setStatus(itemId, Status.CHECKED)
        _uiState.update { it.copy(undoItemId = itemId, undoItemName = item.name.value) }
    }

    /** Notes: tapping a shown checked row's body un-checks it back to todo. */
    fun uncheck(itemId: String): Job = viewModelScope.launch { itemsRepo.setStatus(itemId, Status.TODO) }

    fun undoCheckOff(): Job = viewModelScope.launch {
        val itemId = _uiState.value.undoItemId ?: return@launch
        itemsRepo.setStatus(itemId, Status.TODO)
        _uiState.update { it.copy(undoItemId = null, undoItemName = null) }
    }

    fun dismissUndo() = _uiState.update { it.copy(undoItemId = null, undoItemName = null) }

    /**
     * The post-trip 'finish up' bulk action: move every checked item to backlog at once (T-35).
     * Arms its own undo snackbar and supersedes any pending single-item check-off undo, so only one
     * snackbar is ever showing.
     */
    fun clearChecked(): Job = viewModelScope.launch {
        val cleared = itemsRepo.clearChecked(listId)
        if (cleared.isNotEmpty()) {
            _uiState.update { it.copy(clearedCheckedIds = cleared, undoItemId = null, undoItemName = null) }
        }
    }

    fun undoClearChecked(): Job = viewModelScope.launch {
        val ids = _uiState.value.clearedCheckedIds
        if (ids.isNotEmpty()) itemsRepo.setStatusBulk(ids, Status.CHECKED)
        _uiState.update { it.copy(clearedCheckedIds = emptyList()) }
    }

    fun dismissClearUndo() = _uiState.update { it.copy(clearedCheckedIds = emptyList()) }

    private fun regroup() {
        _uiState.update { state ->
            val visible = if (state.showChecked) todoItems + checkedItems else todoItems
            state.copy(groups = groupByCategory(visible, categoryOrder), checkedCount = checkedItems.size)
        }
    }
}

private fun groupByCategory(items: List<ItemEntity>, categoryOrder: List<String>): List<ItemGroup> {
    val byCategory = items.groupBy { it.category.value }
    val ordered = categoryOrder.filter { byCategory.containsKey(it) }
    val leftover = byCategory.keys.filterNotNull().filter { it !in categoryOrder }.sortedBy { it.lowercase() }
    val keys = ordered + leftover + (if (byCategory.containsKey(null)) listOf(null) else emptyList())
    return keys.map { key ->
        ItemGroup(category = key, items = byCategory.getValue(key).sortedBy { it.name.value.lowercase() })
    }
}

/** Notes (Price rendering): falls back to the account's default currency when the item has none. */
internal fun formatPrice(item: ItemEntity, defaultCurrency: String?): String? {
    val json = item.price.value ?: return null
    val price = Json.decodeFromString<Price>(json)
    val currency = price.currency ?: defaultCurrency
    return if (currency != null) "${price.amount} $currency" else price.amount
}
