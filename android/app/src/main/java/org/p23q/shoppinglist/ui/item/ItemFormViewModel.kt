package org.p23q.shoppinglist.ui.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import javax.inject.Inject

data class ItemFormUiState(
    val isEditMode: Boolean = false,
    val itemId: String? = null,
    val name: String = "",
    val nameError: String? = null,
    val suggestions: List<ItemEntity> = emptyList(),
    val category: String = "",
    val categorySuggestions: List<String> = emptyList(),
    val stores: List<String> = emptyList(),
    val storeInput: String = "",
    val quantity: String = "",
    val priceAmount: String = "",
    val priceCurrency: String = "",
    val note: String = "",
    val status: Status = Status.TODO,
    val isDeleteConfirmOpen: Boolean = false,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
)

/**
 * Shared by [AddItemDialog] and [EditItemDialog] (Notes: both edit the same field set). Add mode
 * starts blank and offers live registry suggestions as the name is typed; picking one reuses that
 * item (setting it `todo`) instead of creating a duplicate. Edit mode pre-fills from an existing
 * item and additionally exposes status + delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemFormViewModel @Inject constructor(
    private val itemsRepo: ItemsRepo,
    private val sessionState: SessionState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemFormUiState())
    val uiState: StateFlow<ItemFormUiState> = _uiState.asStateFlow()

    private var listId: String = ""
    private val nameQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            nameQuery.flatMapLatest { query ->
                if (_uiState.value.isEditMode || query.isBlank()) flowOf(emptyList()) else itemsRepo.searchRegistry(listId, query)
            }.collect { results -> _uiState.update { it.copy(suggestions = results) } }
        }
    }

    fun startAdd(listId: String) {
        this.listId = listId
        _uiState.value = ItemFormUiState(isEditMode = false, priceCurrency = sessionState.defaultCurrency ?: "")
        loadCategorySuggestions()
    }

    fun startEdit(itemId: String): Job = viewModelScope.launch {
        val item = itemsRepo.getById(itemId) ?: return@launch
        listId = item.listId
        val price = itemsRepo.decodePrice(item.price.value)
        _uiState.value = ItemFormUiState(
            itemId = item.id,
            isEditMode = true,
            name = item.name.value,
            category = item.category.value ?: "",
            stores = itemsRepo.decodeStores(item.stores.value),
            quantity = item.quantity.value ?: "",
            priceAmount = price?.amount ?: "",
            priceCurrency = price?.currency ?: sessionState.defaultCurrency ?: "",
            note = item.note.value ?: "",
            status = Status.fromWireValue(item.status.value),
        )
        loadCategorySuggestions()
    }

    private fun loadCategorySuggestions() = viewModelScope.launch {
        val categories = itemsRepo.distinctCategories(listId).first()
        _uiState.update { it.copy(categorySuggestions = categories) }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, nameError = null) }
        nameQuery.value = value
    }

    fun onCategoryChange(value: String) = _uiState.update { it.copy(category = value) }

    fun onStoreInputChange(value: String) = _uiState.update { it.copy(storeInput = value) }

    fun addStore() {
        val store = _uiState.value.storeInput.trim()
        if (store.isBlank()) return
        _uiState.update { it.copy(stores = it.stores + store, storeInput = "") }
    }

    fun removeStore(store: String) = _uiState.update { it.copy(stores = it.stores - store) }

    fun onQuantityChange(value: String) = _uiState.update { it.copy(quantity = value) }

    fun onPriceAmountChange(value: String) = _uiState.update { it.copy(priceAmount = value) }

    fun onPriceCurrencyChange(value: String) = _uiState.update { it.copy(priceCurrency = value) }

    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    fun onStatusChange(status: Status) = _uiState.update { it.copy(status = status) }

    /** Notes (Add dialog): picking an existing suggestion sets it `todo` and prefills the form. */
    fun pickSuggestion(item: ItemEntity): Job = viewModelScope.launch {
        itemsRepo.setStatus(item.id, Status.TODO)
        val price = itemsRepo.decodePrice(item.price.value)
        _uiState.update {
            it.copy(
                itemId = item.id,
                name = item.name.value,
                nameError = null,
                suggestions = emptyList(),
                category = item.category.value ?: "",
                stores = itemsRepo.decodeStores(item.stores.value),
                quantity = item.quantity.value ?: "",
                priceAmount = price?.amount ?: "",
                priceCurrency = price?.currency ?: it.priceCurrency,
                note = item.note.value ?: "",
                status = Status.TODO,
            )
        }
    }

    /** Returns null only for a trivial synchronous validation failure (blank name); Job otherwise. */
    fun save(): Job? {
        val state = _uiState.value
        val trimmedName = state.name.trim()
        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(nameError = "Name is required") }
            return null
        }
        return viewModelScope.launch {
            val collision = itemsRepo.findByExactName(listId, trimmedName, excludingId = state.itemId ?: "")
            if (collision != null) {
                _uiState.update { it.copy(nameError = "An item named \"$trimmedName\" already exists") }
                return@launch
            }
            val targetId = state.itemId ?: itemsRepo.createItem(listId, trimmedName, status = Status.TODO)
            if (state.itemId != null) {
                itemsRepo.rename(targetId, trimmedName)
            }
            itemsRepo.setCategory(targetId, state.category.trim().ifBlank { null })
            itemsRepo.setStores(targetId, state.stores)
            itemsRepo.setQuantity(targetId, state.quantity.trim().ifBlank { null })
            itemsRepo.setPrice(
                targetId,
                amount = state.priceAmount.trim().ifBlank { null },
                currency = state.priceCurrency.trim().ifBlank { null },
            )
            itemsRepo.setNote(targetId, state.note.trim().ifBlank { null })
            if (state.isEditMode) {
                itemsRepo.setStatus(targetId, state.status)
            }
            _uiState.update { it.copy(nameError = null, isSaved = true, itemId = targetId) }
        }
    }

    fun requestDelete() = _uiState.update { it.copy(isDeleteConfirmOpen = true) }

    fun cancelDelete() = _uiState.update { it.copy(isDeleteConfirmOpen = false) }

    fun confirmDelete(): Job? {
        val itemId = _uiState.value.itemId ?: return null
        return viewModelScope.launch {
            itemsRepo.delete(itemId)
            _uiState.update { it.copy(isDeleteConfirmOpen = false, isDeleted = true) }
        }
    }
}
