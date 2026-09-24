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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.InviteForMeDto
import org.p23q.shoppinglist.core.api.RedeemInviteRequest
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.ListEntity
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncState
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
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

/**
 * One account's part of the overview (T-292): its lists, and the invites waiting for it, open and
 * ignored. With one account there is one section and the screen draws no header for it.
 */
data class OverviewSection(
    val account: AccountEntity,
    val lists: List<ListEntity>,
    val invites: List<InviteForMeDto>,
    val ignoredInvites: List<InviteForMeDto>,
)

data class OverviewUiState(
    val lists: List<ListEntity> = emptyList(),
    /**
     * Every account, in the overview's order: server accounts in the user's order, then any
     * account that lives only on this phone.
     */
    val accounts: List<AccountEntity> = emptyList(),
    /** Open (todo) item count per list id, shown on each card (T-42). */
    val openCounts: Map<String, Int> = emptyMap(),
    val isCreateDialogOpen: Boolean = false,
    val newListName: String = "",
    /** Kind for the list being created (T-110); shopping preselected, as before. */
    val newListKind: String = ListKind.DEFAULT,
    /** Currency for an expenses list being created (T-151); prefilled from the account default. */
    val newListCurrency: String = "",
    /** The account the new list goes to (T-292); only offered as a choice with several accounts. */
    val newListAccountId: String? = null,
    /** Total spent and this account's balance per expenses list, for its card (T-154). */
    val expenseSummaries: Map<String, ExpenseSummary> = emptyMap(),
    val sync: SyncState = SyncState(),
    /** The list holding a quarantined row, so the "needs attention" banner can open it (T-47). */
    val attentionListId: String? = null,
    /** True while a user-initiated pull-to-refresh sync is running, for the spinner (T-36). */
    val isRefreshing: Boolean = false,
    /** Invites addressed to each account, by local account id (T-233, T-292). Empty offline. */
    val invitesByAccount: Map<String, List<InviteForMeDto>> = emptyMap(),
    /** The ones this device ignored, per account: greyed, at the bottom of their section, still joinable. */
    val ignoredInviteIds: Map<String, Set<String>> = emptyMap(),
    val joiningInviteId: String? = null,
    val inviteError: UiText? = null,
    /** The account whose section shows [inviteError]. */
    val inviteErrorAccountId: String? = null,
    /** Set once a Join went through; the screen opens this list, then calls [OverviewViewModel.joinedListOpened]. */
    val joinedListId: String? = null,
) {
    /** Whether the phone holds more than one account: sections get headers and cards a marker. */
    val several: Boolean get() = accounts.size > 1

    /** Every invite, of every account. */
    val invites: List<InviteForMeDto> get() = invitesByAccount.values.flatten()

    /** The overview's sections, one per account, in [accounts]' order. */
    val sections: List<OverviewSection>
        get() = accounts.map { account ->
            val invites = invitesByAccount[account.id].orEmpty()
            val ignored = ignoredInviteIds[account.id].orEmpty()
            OverviewSection(
                account = account,
                lists = lists.filter { it.accountId == account.id },
                invites = invites.filter { it.id !in ignored },
                ignoredInvites = invites.filter { it.id in ignored },
            )
        }
}

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val listsRepo: ListsRepo,
    private val itemsRepo: ItemsRepo,
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
    private val lastOpened: LastOpenedListStore,
    private val syncer: Syncer,
    syncStatus: SyncStatus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            registry.load()
            registry.accounts.collect { accounts ->
                val ordered = overviewOrder(accounts)
                _uiState.update { state ->
                    state.copy(
                        accounts = ordered,
                        ignoredInviteIds = ordered.associate { it.id to AccountRegistry.decodeIds(it.ignoredInviteIdsJson) },
                        // A removed account's invites go with it, and a signed-out one's cannot be joined.
                        invitesByAccount = state.invitesByAccount.filterKeys { id -> ordered.any { it.id == id && it.signedIn } },
                    )
                }
            }
        }
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
                registry.load()
                val itemsByList = allExpenseItems.groupBy { it.listLocalId }
                val summaries = lists.filter { ListKind.isExpenses(it.kind.value) }.associate { list ->
                    val expenses = (itemsByList[list.localId] ?: emptyList())
                        .mapNotNull { itemsRepo.decodeExpense(it.expense.value) }
                    val members = listsRepo.decodeMembers(list.membersJson)
                    // Where the list's own account stands: another account on this phone may be
                    // on the same list, with its own row and its own balance (T-292).
                    val me = registry.get(list.accountId)?.accountId
                    val balance = ExpenseMath.balancesFor(expenses, members.map { m -> m.accountId })
                        .firstOrNull { b -> b.accountId == me }
                    list.localId to ExpenseSummary(
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
                    itemsRepo.firstBlockedItem()?.listLocalId ?: listsRepo.firstBlockedListId()
                } else {
                    null
                }
                _uiState.update { it.copy(sync = sync, attentionListId = attentionListId) }
            }
        }
    }

    /**
     * Opens the New-list dialog, for the account of the list opened last (T-292) or, without one,
     * the first account in the overview's order.
     */
    fun openCreateDialog() = _uiState.update { state ->
        val lastAccountId = lastOpened.lastOpenedListId?.let { id -> state.lists.firstOrNull { it.localId == id } }?.accountId
        val account = state.accounts.firstOrNull { it.id == lastAccountId } ?: state.accounts.firstOrNull()
        state.copy(
            isCreateDialogOpen = true,
            newListName = "",
            newListKind = ListKind.DEFAULT,
            newListAccountId = account?.id,
            newListCurrency = account?.defaultCurrency.orEmpty(),
        )
    }

    fun onNewListCurrencyChange(value: String) = _uiState.update { it.copy(newListCurrency = value) }

    /** Another account for the new list; its default currency replaces the old one's, unless one was typed. */
    fun onNewListAccountChange(accountId: String) = _uiState.update { state ->
        val previous = state.accounts.firstOrNull { it.id == state.newListAccountId }?.defaultCurrency.orEmpty()
        val next = state.accounts.firstOrNull { it.id == accountId } ?: return@update state
        state.copy(
            newListAccountId = next.id,
            newListCurrency = if (state.newListCurrency == previous) next.defaultCurrency.orEmpty() else state.newListCurrency,
        )
    }

    fun dismissCreateDialog() = _uiState.update { it.copy(isCreateDialogOpen = false) }

    fun onNewListNameChange(value: String) = _uiState.update { it.copy(newListName = value) }

    fun onNewListKindChange(kind: String) = _uiState.update { it.copy(newListKind = kind) }

    /** Returns the launched Job, or null if the name was blank (dialog stays open, no-op) or there is no account. */
    fun createList(): Job? {
        val name = _uiState.value.newListName.trim()
        if (name.isBlank()) return null
        // The server refuses an expenses list without one, and the kind is fixed for life, so
        // there is no second chance to supply it later.
        if (ListKind.isExpenses(_uiState.value.newListKind) && _uiState.value.newListCurrency.isBlank()) {
            return null
        }
        val state = _uiState.value
        // The overview is only reached with an account, so there is one to create it in.
        val accountId = state.newListAccountId?.takeIf { id -> state.accounts.any { it.id == id } }
            ?: state.accounts.firstOrNull()?.id
            ?: return null
        return viewModelScope.launch {
            listsRepo.create(
                accountId,
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
        lastOpened.lastOpenedListId = listId
    }

    /** Manual pull-to-refresh: an immediate foreground sync of every account, with a visible spinner (T-36). */
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
     * The invites waiting for each account that can ask (T-233): signed in and not too old for its
     * server, each asked of its own server. Online only, like the members screen: when an
     * account's request fails its invites are simply absent, or keep their last good answer.
     */
    fun loadInvites(): Job = viewModelScope.launch {
        val accounts = registry.load().filter { it.isServer && it.signedIn && !it.outdated && sessions.hasToken(it.id) }
        accounts.map { account -> launch { loadInvites(account.id) } }.joinAll()
    }

    private suspend fun loadInvites(accountId: String) {
        try {
            val invites = sessions.get(accountId).api.pendingInvites().invites
            // An ignored id the server no longer offers is dead (used, withdrawn or expired):
            // forget it, so the stored set cannot grow without bound.
            val stored = registry.get(accountId)?.let { AccountRegistry.decodeIds(it.ignoredInviteIdsJson) } ?: return
            val live = stored.filterTo(mutableSetOf()) { id -> invites.any { it.id == id } }
            if (live != stored) setIgnored(accountId, live)
            _uiState.update { it.copy(invitesByAccount = it.invitesByAccount + (accountId to invites)) }
        } catch (e: ApiException) {
            // A server without the endpoint, or a session that just ended: the same as offline —
            // nothing to show, nothing to say. ApiException must be caught before IOException,
            // which it extends (T-264), or this branch is unreachable dead code.
        } catch (e: IOException) {
            // Offline: nothing to show, nothing to say.
        } catch (e: IllegalStateException) {
            // The account went away while its request was out.
        }
    }

    /** A device-local choice: the invite moves to the greyed part of its section, where Join still is. */
    fun ignoreInvite(accountId: String, inviteId: String) {
        val next = _uiState.value.ignoredInviteIds[accountId].orEmpty() + inviteId
        setIgnored(accountId, next)
    }

    private fun setIgnored(accountId: String, ids: Set<String>) {
        registry.updateInBackground(accountId) { it.copy(ignoredInviteIdsJson = AccountRegistry.encodeIds(ids)) }
        _uiState.update { it.copy(ignoredInviteIds = it.ignoredInviteIds + (accountId to ids)) }
    }

    /** Join from the overview: the same path as a pasted link — redeem, pull the list, open it — for the invite's account. */
    fun joinInvite(accountId: String, invite: InviteForMeDto): Job = viewModelScope.launch {
        _uiState.update { it.copy(joiningInviteId = invite.id, inviteError = null, inviteErrorAccountId = accountId) }
        try {
            val serverId = sessions.get(accountId).api.redeemInvite(RedeemInviteRequest(invite.token)).listId
            syncer.syncJoined(accountId, serverId)
            // The server names the list by its server id; the screens need this phone's row of it,
            // which the sync just pulled. Without it (the pull failed) there is nothing to open yet.
            val listId = listsRepo.localIdForServerId(accountId, serverId) ?: run {
                _uiState.update { it.copy(joiningInviteId = null, inviteError = UiText.res(R.string.error_offline)) }
                return@launch
            }
            lastOpened.lastOpenedListId = listId
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

/** The overview's order of accounts (T-292): server accounts in the user's order, then this phone's own. */
internal fun overviewOrder(accounts: List<AccountEntity>): List<AccountEntity> =
    accounts.sortedWith(compareBy<AccountEntity> { if (it.isServer) 0 else 1 }.thenBy { it.sortOrder })
