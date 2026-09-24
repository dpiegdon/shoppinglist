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
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.core.api.InviteForMeDto
import org.p23q.shoppinglist.core.api.RedeemInviteRequest
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException
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
    /** Invites addressed to this account, offered below the lists (T-233). Empty offline. */
    val invites: List<InviteForMeDto> = emptyList(),
    /** The ones this device ignored: greyed, at the very bottom, still joinable. */
    val ignoredInviteIds: Set<String> = emptySet(),
    val joiningInviteId: String? = null,
    val inviteError: UiText? = null,
    /** Set once a Join went through; the screen opens this list, then calls [OverviewViewModel.joinedListOpened]. */
    val joinedListId: String? = null,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val listsRepo: ListsRepo,
    private val itemsRepo: ItemsRepo,
    private val sessionState: SessionState,
    private val syncer: Syncer,
    syncStatus: SyncStatus,
    private val apiProvider: ApiProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverviewUiState(ignoredInviteIds = sessionState.ignoredInviteIds))
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    init {
        loadInvites()
        viewModelScope.launch {
            combine(listsRepo.activeLists(), itemsRepo.openItemCounts(), ::Pair).collect { (lists, counts) ->
                _uiState.update { it.copy(lists = lists, openCounts = counts) }
            }
        }
        viewModelScope.launch {
            // Separate from the counts above because it needs each expenses list's items, not a
            // per-list count. Shopping lists never enter this map.
            //
            // Driven by BOTH the lists flow AND a live items flow (T-265, the pattern
            // ExpenseListViewModel's own init already uses): recording or editing an entry touches
            // only the items table, not the list row, so a summary that only re-derives when
            // activeLists() emits kept showing the old total and balance until something else
            // changed the list (a rename, a pull, process death).
            combine(listsRepo.activeLists(), itemsRepo.expenseItems(), ::Pair).collect { (lists, allExpenseItems) ->
                val itemsByList = allExpenseItems.groupBy { it.listId }
                val summaries = lists.filter { ListKind.isExpenses(it.kind.value) }.associate { list ->
                    val expenses = (itemsByList[list.id] ?: emptyList())
                        .mapNotNull { itemsRepo.decodeExpense(it.expense.value) }
                    val members = listsRepo.decodeMembers(list.membersJson)
                    val balance = ExpenseMath.balancesFor(expenses, members.map { m -> m.accountId })
                        .firstOrNull { b -> b.accountId == sessionState.accountId }
                    list.id to ExpenseSummary(
                        // Net spent, as on the ledger itself: income off it, settlements counting
                        // for nothing (T-245).
                        totalCents = ExpenseMath.spentTotals(expenses).netCents,
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
                // A blocked list row counts too (T-198), so fall back to it when no item is blocked.
                val attentionListId = if (sync.blockedCount > 0) {
                    itemsRepo.firstBlockedItem()?.listId ?: listsRepo.firstBlockedListId()
                } else {
                    null
                }
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
            loadInvites().join()
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * The invites waiting for this account (T-233). Online only, like the members screen: when the
     * request fails the section is simply absent, or keeps its last good answer.
     */
    fun loadInvites(): Job = viewModelScope.launch {
        try {
            val invites = apiProvider.get().pendingInvites().invites
            // An ignored id the server no longer offers is dead (used, withdrawn or expired):
            // forget it, so the stored set cannot grow without bound.
            val live = sessionState.ignoredInviteIds.filterTo(mutableSetOf()) { id -> invites.any { it.id == id } }
            if (live != sessionState.ignoredInviteIds) sessionState.ignoredInviteIds = live
            _uiState.update { it.copy(invites = invites, ignoredInviteIds = live) }
        } catch (e: ApiException) {
            // A server without the endpoint, or a session that just ended: the same as offline —
            // nothing to show, nothing to say. ApiException must be caught before IOException,
            // which it extends (T-264), or this branch is unreachable dead code.
        } catch (e: IOException) {
            // Offline: nothing to show, nothing to say.
        }
    }

    /** A device-local choice: the invite moves to the greyed section at the bottom, where Join still is. */
    fun ignoreInvite(inviteId: String) {
        val next = sessionState.ignoredInviteIds + inviteId
        sessionState.ignoredInviteIds = next
        _uiState.update { it.copy(ignoredInviteIds = next) }
    }

    /** Join from the overview: the same path as a pasted link — redeem, pull the list, open it. */
    fun joinInvite(invite: InviteForMeDto): Job = viewModelScope.launch {
        _uiState.update { it.copy(joiningInviteId = invite.id, inviteError = null) }
        try {
            val listId = apiProvider.get().redeemInvite(RedeemInviteRequest(invite.token)).listId
            syncer.syncNow(listOf(listId))
            sessionState.lastOpenedListId = listId
            _uiState.update { it.copy(joiningInviteId = null, joinedListId = listId) }
        } catch (e: ApiException) {
            val message = ErrorText.of(e, R.string.redeem_msg_failed, mapOf("invalid_token" to R.string.api_error_invite_not_found))
            _uiState.update { it.copy(joiningInviteId = null, inviteError = message) }
            loadInvites() // a used, withdrawn or expired invite drops out of the section
        } catch (e: IOException) {
            _uiState.update { it.copy(joiningInviteId = null, inviteError = UiText.res(R.string.error_offline)) }
        }
    }

    fun joinedListOpened() = _uiState.update { it.copy(joinedListId = null) }
}
