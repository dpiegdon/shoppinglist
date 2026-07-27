package org.p23q.shoppinglist.ui.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.CategoryCanon
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import javax.inject.Inject
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

data class ItemFormUiState(
    val isEditMode: Boolean = false,
    val itemId: String? = null,
    val name: String = "",
    val nameError: UiText? = null,
    val suggestions: List<ItemEntity> = emptyList(),
    val category: String = "",
    val categorySuggestions: List<String> = emptyList(),
    /** False on a checklist (T-110): hides stores / quantity / price. */
    val showShoppingFields: Boolean = true,
    val stores: List<String> = emptyList(),
    val storeInput: String = "",
    val quantity: String = "",
    val priceAmount: String = "",
    val priceError: UiText? = null,
    val priceCurrency: String = "",
    val currencyError: UiText? = null,
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
 * starts blank and, while Name is still empty, offers the most likely re-adds — recent backlog
 * items, most-recently-touched first (T-52) — then switches to live registry search once the user
 * types. Picking any suggestion reuses that item (setting it `todo`) instead of creating a
 * duplicate. Edit mode pre-fills from an existing item and additionally exposes status + delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemFormViewModel @Inject constructor(
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    private val sessionState: SessionState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemFormUiState())
    val uiState: StateFlow<ItemFormUiState> = _uiState.asStateFlow()

    private var listId: String = ""
    private val listIdFlow = MutableStateFlow("")
    private val nameQuery = MutableStateFlow("")

    /**
     * The normalized values the form was seeded with in [startEdit] / [pickSuggestion] — the
     * baseline [performSave] diffs against so an edit re-stamps only genuinely changed fields and
     * never reverts a collaborator's concurrent edit to an untouched one (T-88). Null in plain add
     * mode: a brand-new row has nothing to diff against, so every provided field is a first write.
     */
    private var loadedSnapshot: ItemSnapshot? = null

    init {
        viewModelScope.launch {
            combine(listIdFlow, nameQuery) { id, query -> id to query }
                .flatMapLatest { (id, query) ->
                    when {
                        _uiState.value.isEditMode || id.isBlank() -> flowOf(emptyList())
                        query.isBlank() -> itemsRepo.itemsForListByStatus(id, Status.BACKLOG)
                            .map { backlog -> backlog.sortedByDescending { it.status.updatedAt }.take(BACKLOG_SUGGESTION_LIMIT) }
                        else -> itemsRepo.searchRegistry(id, query)
                    }
                }.collect { results -> _uiState.update { it.copy(suggestions = results) } }
        }
    }

    fun startAdd(listId: String) {
        this.listId = listId
        // isEditMode must land in _uiState BEFORE listIdFlow's new value can trigger the
        // suggestions flow, or it could briefly re-read a stale isEditMode from before this call.
        _uiState.value = ItemFormUiState(isEditMode = false, priceCurrency = sessionState.defaultCurrency ?: "")
        loadedSnapshot = null
        listIdFlow.value = listId
        loadCategorySuggestions()
        loadListKind()
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
        // Seeded status equals the item's stored status here, so snapshotFrom captures the baseline
        // to diff against on save (T-88).
        loadedSnapshot = snapshotFrom(_uiState.value)
        listIdFlow.value = item.listId
        loadCategorySuggestions()
        loadListKind()
    }

    /** Kind drives which fields the form renders (T-110); resolved per list open. */
    private fun loadListKind() = viewModelScope.launch {
        val kind = listsRepo.getById(listId)?.kind?.value
        _uiState.update { it.copy(showShoppingFields = ListKind.showsShoppingFields(kind)) }
    }

    private fun loadCategorySuggestions() = viewModelScope.launch {
        // Canonical (case-insensitive-distinct) set, so we never offer "Group" and "group"
        // separately (T-108). Mirrors the web item dialog's suggestions.
        val raw = itemsRepo.categoryValues(listId).first()
        val order = listsRepo.getById(listId)?.let { listsRepo.decodeCategoryOrder(it.categoryOrder.value) }
            ?: emptyList()
        _uiState.update { it.copy(categorySuggestions = CategoryCanon.distinctCanonical(raw, order)) }
    }

    /**
     * The shared "canonicalize a category" write (T-108): rewrite every item in the [fromKey]
     * category to [toName] and fix the matching category_order entry. Used here for the recase-all
     * gesture; the list-settings rename runs the same plan.
     */
    private suspend fun applyCategoryRename(fromKey: String, toName: String) {
        val items = itemsRepo.activeItemsForListOnce(listId)
        val order = listsRepo.getById(listId)?.let { listsRepo.decodeCategoryOrder(it.categoryOrder.value) }
            ?: emptyList()
        val plan = CategoryCanon.planRename(items.map { it.id to (it.category.value ?: "") }, order, fromKey, toName)
        itemsRepo.setCategoryBulk(plan.itemIds, toName.trim())
        if (plan.orderChanged) listsRepo.setCategoryOrder(listId, plan.nextCategoryOrder)
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
        // Snapshot the picked item's actual field values as the diff baseline. The uiState status
        // was just set to TODO (the intended new value), so override it with the item's real status
        // — that way an unmodified adopt correctly diffs as "only status changed" (backlog -> todo).
        loadedSnapshot = snapshotFrom(_uiState.value).copy(status = Status.fromWireValue(item.status.value))
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
            _uiState.update { it.copy(nameError = UiText.res(R.string.item_msg_name_required)) }
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
                _uiState.update { it.copy(nameError = UiText.res(R.string.item_msg_duplicate_name, trimmedName)) }
                return@launch
            }
            val targetId = state.itemId ?: itemsRepo.createItem(listId, trimmedName, status = Status.TODO)
            val category = state.category.trim().ifBlank { null }
            val quantity = state.quantity.trim().ifBlank { null }
            val note = state.note.trim().ifBlank { null }
            // Currency only means something alongside an amount (the server stores price as
            // amount+currency-or-null), so drop a stray currency when there's no amount.
            val currency = if (normalizedAmount != null) normalizedCurrency else null
            if (state.itemId == null) {
                // Brand-new row: every provided field is a first write on a fresh row, so nothing
                // here can stomp a collaborator's edit — write them all (unchanged behavior).
                itemsRepo.setCategory(targetId, category)
                itemsRepo.setStores(targetId, state.stores)
                itemsRepo.setQuantity(targetId, quantity)
                itemsRepo.setPrice(targetId, amount = normalizedAmount, currency = currency)
                itemsRepo.setNote(targetId, note)
            } else {
                // Existing row (edit or adopt): stamp a fresh LWW clock only on fields whose value
                // actually differs from the snapshot the form was seeded with, so an untouched field
                // keeps its clock and never reverts a collaborator's concurrent edit to it (T-88). A
                // zero-change save therefore writes nothing at all (the dialog still closes below).
                val snap = loadedSnapshot
                // Apply status on Save, not at pick time (T-33): an edited item takes the chosen
                // status; a picked existing item joins the list as todo.
                val targetStatus = if (state.isEditMode) state.status else Status.TODO
                if (snap == null || trimmedName != snap.name) itemsRepo.rename(targetId, trimmedName)
                if (snap == null || targetStatus != snap.status) itemsRepo.setStatus(targetId, targetStatus)
                if (snap == null || category != snap.category) {
                    // Same word, different case (T-108): this is "fix the whole category's casing",
                    // not a one-item change — a per-item recase is a no-op under case-insensitive
                    // grouping. Recase every item in the category (incl. this one) + fix the order.
                    val old = snap?.category
                    if (old != null && category != null && CategoryCanon.key(old) == CategoryCanon.key(category)) {
                        applyCategoryRename(CategoryCanon.key(old), category)
                    } else {
                        itemsRepo.setCategory(targetId, category)
                    }
                }
                if (snap == null || state.stores != snap.stores) itemsRepo.setStores(targetId, state.stores)
                if (snap == null || quantity != snap.quantity) itemsRepo.setQuantity(targetId, quantity)
                if (snap == null || normalizedAmount != snap.priceAmount || currency != snap.priceCurrency) {
                    itemsRepo.setPrice(targetId, amount = normalizedAmount, currency = currency)
                }
                if (snap == null || note != snap.note) itemsRepo.setNote(targetId, note)
            }
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
                loadedSnapshot = null
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

    /**
     * The form's current values, normalized exactly the way [performSave] normalizes before writing
     * (trim + blank->null, price parsed to its stored form), so diffing a seeded snapshot against a
     * save's values reliably reports only genuine changes (T-88).
     */
    private fun snapshotFrom(state: ItemFormUiState): ItemSnapshot {
        val amount = (parsePriceAmount(state.priceAmount) as? PriceParse.Valid)?.value
        val currency = (parseCurrency(state.priceCurrency) as? PriceParse.Valid)?.value
        return ItemSnapshot(
            name = state.name.trim(),
            category = state.category.trim().ifBlank { null },
            stores = state.stores,
            quantity = state.quantity.trim().ifBlank { null },
            priceAmount = amount,
            priceCurrency = if (amount != null) currency else null,
            note = state.note.trim().ifBlank { null },
            status = state.status,
        )
    }

    private companion object {
        const val BACKLOG_SUGGESTION_LIMIT = 5
    }
}

/** Normalized snapshot of an item's editable fields, the diff baseline for change-scoped saves (T-88). */
private data class ItemSnapshot(
    val name: String,
    val category: String?,
    val stores: List<String>,
    val quantity: String?,
    val priceAmount: String?,
    val priceCurrency: String?,
    val note: String?,
    val status: Status,
)

/** Result of parsing a user-typed price/currency field. [Valid.value] is null when the field is blank. */
internal sealed interface PriceParse {
    data class Valid(val value: String?) : PriceParse
    data class Invalid(val message: UiText) : PriceParse
}

// [0-9] rather than \d, to stay explicitly in step with the server (T-125). Java's \d is already
// ASCII-only so this is a no-op here — but the server's Python \d is NOT, which is how the three
// copies of this "identical" pattern came to mean different things.
// Siblings: server sync.py PRICE_AMOUNT_RE, web src/lib/priceParse.ts PRICE_AMOUNT_RE.
private val PRICE_AMOUNT_RE = Regex("^[0-9]+(\\.[0-9]{1,2})?$")
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
    else PriceParse.Invalid(UiText.res(R.string.item_msg_price_invalid))
}

/** Uppercases and validates a 3-letter ISO-4217 code; blank -> [PriceParse.Valid] with null. */
internal fun parseCurrency(raw: String): PriceParse {
    val code = raw.trim().uppercase()
    if (code.isBlank()) return PriceParse.Valid(null)
    return if (CURRENCY_RE.matches(code)) PriceParse.Valid(code)
    else PriceParse.Invalid(UiText.res(R.string.item_msg_currency_invalid))
}
