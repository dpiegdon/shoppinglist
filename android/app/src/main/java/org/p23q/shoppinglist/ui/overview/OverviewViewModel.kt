package org.p23q.shoppinglist.ui.overview

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
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.ExpenseMath
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import javax.inject.Inject

/** What an expenses list's card shows instead of an open-item count. */
data class ExpenseSummary(
    val totalCents: Long,
    val myBalanceCents: Long?,
    val currency: String,
    /** Closed by vote (T-157), which the overview says (T-181). */
    val closed: Boolean = false,
)

data class OverviewUiState(
    val lists: List<ListEntity> = emptyList(),
    /** Open (todo) item count per list id, shown on each card (T-42). */
    val openCounts: Map<String, Int> = emptyMap(),
    val isCreateDialogOpen: Boolean = false,
    val newListName: String = "",
    /** Kind for the list being created (T-110); shopping preselected, as before. */
    val newListKind: String = ListKind.DEFAULT,
    /** Currency for an expenses list being created (T-151); prefilled from the account default. */
    val newListCurrency: String = "",
    /** Total spent and this account's balance per expenses list, for its card (T-154). */
    val expenseSummaries: Map<String, ExpenseSummary> = emptyMap(),
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
            combine(listsRepo.activeLists(), itemsRepo.openItemCounts(), ::Pair).collect { (lists, counts) ->
                _uiState.update { it.copy(lists = lists, openCounts = counts) }
            }
        }
        viewModelScope.launch {
            // Separate from the counts above because it needs each expenses list's items, not a
            // per-list count. Shopping lists never enter this map.
            listsRepo.activeLists().collect { lists ->
                val summaries = lists.filter { ListKind.isExpenses(it.kind.value) }.associate { list ->
                    val expenses = itemsRepo.activeItemsForListOnce(list.id)
                        .mapNotNull { itemsRepo.decodeExpense(it.expense.value) }
                    val members = listsRepo.decodeMembers(list.membersJson)
                    val balance = ExpenseMath.balancesFor(expenses, members.map { m -> m.accountId })
                        .firstOrNull { b -> b.accountId == sessionState.accountId }
                    list.id to ExpenseSummary(
                        totalCents = expenses.sumOf(ExpenseMath::expenseTotalCents),
                        // A list of one is always square with itself, so its balance says nothing.
                        myBalanceCents = balance?.balanceCents?.takeIf { members.size > 1 },
                        currency = list.currency.value.orEmpty(),
                        closed = list.closedAt != null,
                    )
                }
                _uiState.update { it.copy(expenseSummaries = summaries) }
            }
        }
        viewModelScope.launch {
            syncStatus.state.collect { sync ->
                // Resolve which list the attention banner should open only when something is blocked.
                val attentionListId = if (sync.blockedCount > 0) itemsRepo.firstBlockedItem()?.listId else null
                _uiState.update { it.copy(sync = sync, attentionListId = attentionListId) }
            }
        }
    }

    fun openCreateDialog() = _uiState.update {
        it.copy(
            isCreateDialogOpen = true,
            newListName = "",
            newListKind = ListKind.DEFAULT,
            newListCurrency = sessionState.defaultCurrency.orEmpty(),
        )
    }

    fun onNewListCurrencyChange(value: String) = _uiState.update { it.copy(newListCurrency = value) }

    fun dismissCreateDialog() = _uiState.update { it.copy(isCreateDialogOpen = false) }

    fun onNewListNameChange(value: String) = _uiState.update { it.copy(newListName = value) }

    fun onNewListKindChange(kind: String) = _uiState.update { it.copy(newListKind = kind) }

    /** Returns the launched Job, or null if the name was blank (dialog stays open, no-op). */
    fun createList(): Job? {
        val name = _uiState.value.newListName.trim()
        if (name.isBlank()) return null
        // The server refuses an expenses list without one, and the kind is fixed for life, so
        // there is no second chance to supply it later.
        if (ListKind.isExpenses(_uiState.value.newListKind) && _uiState.value.newListCurrency.isBlank()) {
            return null
        }
        return viewModelScope.launch {
            listsRepo.createList(
                name,
                _uiState.value.newListKind,
                currency = _uiState.value.newListCurrency.takeIf {
                    ListKind.isExpenses(_uiState.value.newListKind)
                },
            )
            _uiState.update {
                it.copy(isCreateDialogOpen = false, newListName = "", newListKind = ListKind.DEFAULT)
            }
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
