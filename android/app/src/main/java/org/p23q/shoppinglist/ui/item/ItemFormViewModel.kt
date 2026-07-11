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
    val priceError: String? = null,
    val priceCurrency: String = "",
    val currencyError: String? = null,
    val note: String = "",
    val status: Status = Status.TODO,
    val isDeleteConfirmOpen: Boolean = false,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
    /** Bumped after a "save & add another" so the dialog refocuses the Name field (T-41). */
    val focusNameSignal: Int = 0,
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

    fun onPriceAmountChange(value: String) = _uiState.update { it.copy(priceAmount = value, priceError = null) }

    fun onPriceCurrencyChange(value: String) = _uiState.update { it.copy(priceCurrency = value, currencyError = null) }

    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    fun onStatusChange(status: Status) = _uiState.update { it.copy(status = status) }

    /**
     * Notes (Add dialog): picking an existing suggestion prefills the form and binds its id, so
     * Save reuses that item instead of creating a duplicate. It does NOT touch the item yet — the
     * item only actually joins the list (status -> todo) on Save, so Cancel leaves it untouched
     * (T-33; this reverses A8's persist-on-pick behavior).
     */
    fun pickSuggestion(item: ItemEntity) {
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
    fun save(): Job? = performSave(closeAfter = true)

    /**
     * Save, then keep the Add dialog open on a fresh blank form with focus back in Name — for adding
     * several items in a burst without reopening the dialog each time (T-41). Add mode only.
     */
    fun saveAndAddAnother(): Job? = performSave(closeAfter = false)

    private fun performSave(closeAfter: Boolean): Job? {
        val state = _uiState.value
        val trimmedName = state.name.trim()
        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(nameError = "Name is required") }
            return null
        }
        // Validate/normalize price BEFORE writing, so a bad value (e.g. "1,99", "2€", "1.999") is
        // caught with an inline error rather than pushed and 422'd by the server — which would abort
        // the whole /sync transaction and wedge the push queue (T-32). Price amount + currency are
        // the only user-typed fields the server validates that the client didn't already constrain.
        val amountParse = parsePriceAmount(state.priceAmount)
        if (amountParse is PriceParse.Invalid) {
            _uiState.update { it.copy(priceError = amountParse.message) }
            return null
        }
        val currencyParse = parseCurrency(state.priceCurrency)
        if (currencyParse is PriceParse.Invalid) {
            _uiState.update { it.copy(currencyError = currencyParse.message) }
            return null
        }
        val normalizedAmount = (amountParse as PriceParse.Valid).value
        val normalizedCurrency = (currencyParse as PriceParse.Valid).value

        return viewModelScope.launch {
            val collision = itemsRepo.findByExactName(listId, trimmedName, excludingId = state.itemId ?: "")
            if (collision != null) {
                _uiState.update { it.copy(nameError = "An item named \"$trimmedName\" already exists") }
                return@launch
            }
            val targetId = state.itemId ?: itemsRepo.createItem(listId, trimmedName, status = Status.TODO)
            if (state.itemId != null) {
                itemsRepo.rename(targetId, trimmedName)
                // Apply status on Save, not at pick time (T-33): an edited item takes the chosen
                // status; a picked existing item joins the list as todo. (A brand-new item was
                // already created todo above, so this branch — itemId != null — skips it.)
                itemsRepo.setStatus(targetId, if (state.isEditMode) state.status else Status.TODO)
            }
            itemsRepo.setCategory(targetId, state.category.trim().ifBlank { null })
            itemsRepo.setStores(targetId, state.stores)
            itemsRepo.setQuantity(targetId, state.quantity.trim().ifBlank { null })
            itemsRepo.setPrice(
                targetId,
                amount = normalizedAmount,
                // Currency only means something alongside an amount (the server stores price as
                // amount+currency-or-null), so drop a stray currency when there's no amount.
                currency = if (normalizedAmount != null) normalizedCurrency else null,
            )
            itemsRepo.setNote(targetId, state.note.trim().ifBlank { null })
            if (closeAfter) {
                _uiState.update { it.copy(nameError = null, isSaved = true, itemId = targetId) }
            } else {
                // Reset to a blank add form and bump the refocus signal so the dialog puts the
                // cursor back in Name for the next item (T-41).
                _uiState.value = ItemFormUiState(
                    isEditMode = false,
                    priceCurrency = sessionState.defaultCurrency ?: "",
                    focusNameSignal = state.focusNameSignal + 1,
                )
                loadCategorySuggestions()
            }
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

/** Result of parsing a user-typed price/currency field. [Valid.value] is null when the field is blank. */
internal sealed interface PriceParse {
    data class Valid(val value: String?) : PriceParse
    data class Invalid(val message: String) : PriceParse
}

private val PRICE_AMOUNT_RE = Regex("^\\d+(\\.\\d{1,2})?$")
private val CURRENCY_RE = Regex("^[A-Z]{3}$")
private const val CURRENCY_SYMBOLS = "€\$£¥"

/**
 * Normalizes a typed amount to the server's decimal-string format: accepts a comma decimal
 * separator and strips whitespace + a leading/trailing currency symbol ("1,99", "2€", " 1.50 " ->
 * "1.99"/"2"/"1.50"), then requires `\d+(\.\d{1,2})?`. Blank -> [PriceParse.Valid] with null (no
 * price). Anything else (letters, >2 decimals) -> [PriceParse.Invalid].
 */
internal fun parsePriceAmount(raw: String): PriceParse {
    val cleaned = buildString {
        for (ch in raw.trim().replace(',', '.')) {
            if (!ch.isWhitespace() && ch !in CURRENCY_SYMBOLS) append(ch)
        }
    }
    if (cleaned.isBlank()) return PriceParse.Valid(null)
    return if (PRICE_AMOUNT_RE.matches(cleaned)) PriceParse.Valid(cleaned)
    else PriceParse.Invalid("Enter an amount like 1.99")
}

/** Uppercases and validates a 3-letter ISO-4217 code; blank -> [PriceParse.Valid] with null. */
internal fun parseCurrency(raw: String): PriceParse {
    val code = raw.trim().uppercase()
    if (code.isBlank()) return PriceParse.Valid(null)
    return if (CURRENCY_RE.matches(code)) PriceParse.Valid(code)
    else PriceParse.Invalid("Use a 3-letter code like EUR")
}
