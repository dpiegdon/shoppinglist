package org.p23q.shoppinglist.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.data.CategoryCanon
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.MemberDto
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.repo.Price
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import java.io.IOException
import javax.inject.Inject

/** A category header ([category] `null` = uncategorized, rendered last) plus its sorted items. */
data class ItemGroup(val category: String?, val items: List<ItemEntity>)

data class ListUiState(
    val listName: String = "",
    /** False on a checklist (T-110): hides the quantity/price detail line on each row. */
    val showShoppingFields: Boolean = true,
    val groups: List<ItemGroup> = emptyList(),
    val showChecked: Boolean = false,
    val defaultCurrency: String? = null,
    val undoItemId: String? = null,
    val undoItemName: String? = null,
    /** Live sync health for the recency line (T-47). */
    val sync: SyncState = SyncState(),
    /** True while a user-initiated pull-to-refresh sync is running, for the spinner (T-36). */
    val isRefreshing: Boolean = false,
    /**
     * The list's collaborators (T-64), fetched once per screen open — best-effort; stays empty
     * offline, which correctly suppresses the last-touched-by badge (fewer members shown is a
     * safe default, never wrong data) rather than erroring the whole screen.
     */
    val members: List<MemberDto> = emptyList(),
    /**
     * Rows that have just been checked off and are animating away (T-128). They are still in
     * [groups] — logically gone, kept on screen only long enough for the exit to be seen, since a
     * row removed from the list in the same frame it changes simply vanishes.
     */
    val exitingItemIds: Set<String> = emptySet(),
)

@HiltViewModel
class ListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    private val syncer: Syncer,
    syncStatus: SyncStatus,
    defaultCurrencyState: DefaultCurrencyState,
    private val showCheckedStore: ShowCheckedStore,
    private val apiProvider: ApiProvider,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ListUiState(defaultCurrency = defaultCurrencyState.currency.value))
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    private var categoryOrder: List<String> = emptyList()
    private var todoItems: List<ItemEntity> = emptyList()
    private var checkedItems: List<ItemEntity> = emptyList()

    /** Guards the async restore below from clobbering a toggle that raced it (e.g. in a fast test). */
    private var showCheckedTouched = false

    // --- check-off exit animation bookkeeping (T-128) ---
    /** What was on screen last time, so a departure can be told from a first render. */
    private var visibleIds: Set<String> = emptySet()
    /** Detects a VIEW change rather than a check-off — see regroup(). */
    private var lastShowChecked: Boolean? = null
    /** One pending removal per exiting row. */
    private val exitTimers = mutableMapOf<String, Job>()

    init {
        // Combine the list row with a SINGLE items stream (todo + checked together — ItemsRepo
        // .itemsForList) and derive the two sets from that one snapshot. Both are observed live, so a
        // rename, category-order change, or another device's sync updates without recreating the
        // screen (T-34); combine holds the first grouped emission until both flows have emitted, so
        // grouping is never computed from a half-loaded snapshot (no category-order flash on open).
        // Crucially, one item stream (not two per-status flows) means an item can never appear in
        // both todo and checked during a status change — that transient duplicate crashed the
        // LazyColumn with a duplicate key.
        viewModelScope.launch {
            combine(
                listsRepo.observeById(listId),
                itemsRepo.itemsForList(listId),
                ::Pair,
            ).collect { (list, items) ->
                categoryOrder = list?.let { listsRepo.decodeCategoryOrder(it.categoryOrder.value) } ?: emptyList()
                val showShopping = ListKind.showsShoppingFields(list?.kind?.value)
                todoItems = items.filter { it.status.value == Status.TODO.wireValue }
                checkedItems = items.filter { it.status.value == Status.CHECKED.wireValue }
                _uiState.update { it.copy(listName = list?.name?.value ?: "", showShoppingFields = showShopping) }
                regroup()
            }
        }
        viewModelScope.launch {
            syncStatus.state.collect { sync -> _uiState.update { it.copy(sync = sync) } }
        }
        // Live, not one-shot (T-55): SessionState's EncryptedSharedPreferences backing isn't
        // observable, so without this an already-open list wouldn't see a Settings currency change
        // until the screen was recreated.
        viewModelScope.launch {
            defaultCurrencyState.currency.collect { currency -> _uiState.update { it.copy(defaultCurrency = currency) } }
        }
        // One-shot, not live (T-64): the badge only needs to know the roster, which changes rarely
        // relative to how often this screen opens. Silently stays empty offline/on error.
        viewModelScope.launch {
            try {
                val response = apiProvider.get().members(listId)
                _uiState.update { it.copy(members = response.members) }
            } catch (e: IOException) {
                // Offline or unreachable — no badges is the safe fallback, not an error state.
            }
        }
        // Restore the app-wide, remembered show-checked choice (persisted, device-local, not synced).
        // One-shot read: only one list screen is open at a time, so it's re-read on each open rather
        // than collected live. Guarded so a toggle before this async read lands isn't overwritten.
        viewModelScope.launch {
            val stored = showCheckedStore.showChecked.first()
            if (!showCheckedTouched) {
                _uiState.update { it.copy(showChecked = stored) }
                regroup()
            }
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

    /** Returns the persistence [Job] (state updates synchronously first, so the UI is immediate). */
    fun toggleShowChecked(): Job {
        showCheckedTouched = true
        val next = !_uiState.value.showChecked
        _uiState.update { it.copy(showChecked = next) }
        regroup()
        // Persist app-wide so every list — and the next app launch — remembers the choice.
        return viewModelScope.launch { showCheckedStore.setShowChecked(next) }
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
        val showChecked = _uiState.value.showChecked
        val visible = if (showChecked) todoItems + checkedItems else todoItems
        val newIds = visible.mapTo(mutableSetOf()) { it.id }

        // A change of VIEW is not a check-off. Turning "show checked" off removes every checked row
        // at once; animating twenty rows sliding away would be slow and would misrepresent what
        // happened. Adopt the new baseline silently instead.
        val viewChanged = lastShowChecked != null && lastShowChecked != showChecked
        lastShowChecked = showChecked
        if (viewChanged) {
            exitTimers.values.forEach { it.cancel() }
            exitTimers.clear()
        } else {
            (visibleIds - newIds).forEach { startExit(it) }
            // A row that came BACK mid-animation (rapid check/uncheck, or a collaborator undoing
            // within the window) must stop exiting at once, or it renders twice.
            newIds.intersect(exitTimers.keys).forEach { cancelExit(it) }
        }
        visibleIds = newIds

        val exiting = exitTimers.keys.toSet()
        val ghosts = (todoItems + checkedItems).filter { it.id in exiting }
        _uiState.update { state ->
            state.copy(
                groups = groupByCategory(visible + ghosts, categoryOrder),
                exitingItemIds = exiting,
            )
        }
    }

    private fun startExit(itemId: String) {
        exitTimers[itemId]?.cancel()
        exitTimers[itemId] = viewModelScope.launch {
            delay(EXIT_ANIMATION_MS)
            exitTimers.remove(itemId)
            regroup()
        }
    }

    private fun cancelExit(itemId: String) {
        exitTimers.remove(itemId)?.cancel()
    }

    /**
     * Re-syncs every [LIVE_SYNC_INTERVAL_MS] for as long as it is collected (T-128).
     *
     * A suspend loop rather than a WorkManager job on purpose: WorkManager's floor is 15 minutes
     * and it is the wrong tool for a foreground loop — it stays responsible for background sync.
     * The caller runs this inside repeatOnLifecycle(RESUMED), so it starts when the list is on
     * screen and is cancelled the moment the app backgrounds or the screen leaves.
     *
     * Failures are swallowed: this is an unattended background refresh, and surfacing a transient
     * network blip as an error every five seconds would be worse than the staleness it reports.
     * SyncStatus already carries the health for the recency line.
     */
    suspend fun liveSyncLoop() {
        while (true) {
            delay(LIVE_SYNC_INTERVAL_MS)
            runCatching { syncer.syncNow(emptyList()) }
        }
    }
}

/**
 * How often an OPEN list re-syncs while the app is foregrounded (T-128).
 *
 * Five seconds is chosen for one scenario: two people shopping the same list in the same shop.
 * Whoever picks the milk first must be visible to the other before they reach for it, and walking
 * between aisles takes longer than this. Affordable because an already-up-to-date /sync measured
 * 3.66 ms server-side — two shoppers at this interval cost well under 0.25% of one core.
 */
const val LIVE_SYNC_INTERVAL_MS = 5_000L

/**
 * How long a checked-off row stays on screen so it can animate away (T-128). Kept in step with the
 * animation in ListScreen — if these disagree the row either vanishes mid-slide or lingers as a
 * dead gap.
 */
const val EXIT_ANIMATION_MS = 300L

private fun groupByCategory(items: List<ItemEntity>, categoryOrder: List<String>): List<ItemGroup> {
    // Case-insensitive (T-108): "Group"/"group" merge into one bucket, keyed by the lowercased
    // category and labelled with the canonical casing (a category_order match, else the most-common
    // casing). "" is the uncategorized bucket, rendered last as a null category.
    val names = CategoryCanon.canonicalNames(items.mapNotNull { it.category.value }, categoryOrder)
    val byKey = items.groupBy { CategoryCanon.key(it.category.value ?: "") }
    val orderedKeys = categoryOrder.map { CategoryCanon.key(it) }
        .filter { it.isNotEmpty() && byKey.containsKey(it) }
        .distinct()
    val leftover = byKey.keys
        .filter { it.isNotEmpty() && it !in orderedKeys }
        .sortedBy { (names[it] ?: it).lowercase() }
    val keys = orderedKeys + leftover + (if (byKey.containsKey("")) listOf("") else emptyList())
    return keys.map { key ->
        ItemGroup(
            category = if (key.isEmpty()) null else names[key] ?: key,
            items = byKey.getValue(key).sortedBy { it.name.value.lowercase() },
        )
    }
}

/** Notes (Price rendering): falls back to the account's default currency when the item has none. */
internal fun formatPrice(item: ItemEntity, defaultCurrency: String?): String? {
    val json = item.price.value ?: return null
    val price = Json.decodeFromString<Price>(json)
    val currency = price.currency ?: defaultCurrency
    return if (currency != null) "${price.amount} $currency" else price.amount
}
