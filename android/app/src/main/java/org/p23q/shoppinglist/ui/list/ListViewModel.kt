package org.p23q.shoppinglist.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.repo.Price
import org.p23q.shoppinglist.ui.Routes
import javax.inject.Inject

/** A category header ([category] `null` = uncategorized, rendered last) plus its sorted items. */
data class ItemGroup(val category: String?, val items: List<ItemEntity>)

data class ListUiState(
    val listName: String = "",
    val groups: List<ItemGroup> = emptyList(),
    val showChecked: Boolean = false,
    val defaultCurrency: String? = null,
    val undoItemId: String? = null,
    val undoItemName: String? = null,
)

@HiltViewModel
class ListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    sessionState: SessionState,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ListUiState(defaultCurrency = sessionState.defaultCurrency))
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    private var categoryOrder: List<String> = emptyList()
    private var todoItems: List<ItemEntity> = emptyList()
    private var checkedItems: List<ItemEntity> = emptyList()

    init {
        viewModelScope.launch {
            val list = listsRepo.getById(listId)
            categoryOrder = list?.let { listsRepo.decodeCategoryOrder(it.categoryOrder.value) } ?: emptyList()
            _uiState.update { it.copy(listName = list?.name?.value ?: "") }
        }
        viewModelScope.launch {
            itemsRepo.itemsForListByStatus(listId, Status.TODO).collect { items ->
                todoItems = items
                regroup()
            }
        }
        viewModelScope.launch {
            itemsRepo.itemsForListByStatus(listId, Status.CHECKED).collect { items ->
                checkedItems = items
                regroup()
            }
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

    private fun regroup() {
        _uiState.update { state ->
            val visible = if (state.showChecked) todoItems + checkedItems else todoItems
            state.copy(groups = groupByCategory(visible, categoryOrder))
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
