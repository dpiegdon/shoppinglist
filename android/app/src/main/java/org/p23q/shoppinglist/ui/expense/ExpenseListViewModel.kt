package org.p23q.shoppinglist.ui.expense

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
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.ListAccounts
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import org.p23q.shoppinglist.ui.list.LIVE_SYNC_INTERVAL_MS
import java.io.IOException
import javax.inject.Inject

/** One expense, with its money tuple already decoded for rendering. */
data class ExpenseRow(val item: ItemEntity, val expense: Expense)

/**
 * A list's expenses in the order they are shown: newest date first, then newest created. Sorted
 * here rather than in SQL, since the date lives inside the expense JSON, which SQLite cannot order
 * by without unpacking it.
 *
 * The former-member numbering follows this order, so the form takes it from here too (T-197) —
 * numbering within one expense would name the same person differently there.
 */
fun expenseRowsOf(items: List<ItemEntity>, itemsRepo: ItemsRepo): List<ExpenseRow> =
    items
        .mapNotNull { item -> itemsRepo.decodeExpense(item.expense.value)?.let { ExpenseRow(item, it) } }
        .sortedWith(
            compareByDescending<ExpenseRow> { it.expense.date }.thenByDescending { it.item.createdAt },
        )

data class ExpenseListUiState(
    val currency: String = "",
    val members: List<ListMember> = emptyList(),
    /** Newest date first; within a date, newest first. */
    val rows: List<ExpenseRow> = emptyList(),
    /** What the ledger spent, took in and the net of the two (T-245). */
    val spent: ExpenseMath.SpentTotals = ExpenseMath.SpentTotals(0, 0, 0),
    val balances: List<ExpenseMath.Balance> = emptyList(),
    /** Numbering for participants who are no longer members (T-152). */
    val formerMemberNumbers: Map<String, Int> = emptyMap(),
    val myAccountId: String? = null,
    /** Who has agreed to close (T-157), and when it closed — both server-maintained. */
    val closeVotes: List<String> = emptyList(),
    val closedAt: Long? = null,
    val isVoting: Boolean = false,
    /** Why the last vote attempt failed, by server code (T-264) — null while it hasn't, or hasn't failed. */
    val voteError: UiText? = null,
    /** Pull-to-refresh in progress (T-167), as on the other lists. */
    val isRefreshing: Boolean = false,
    /** Who pays whom to zero the balances (T-165), in the order the shared algorithm fixes. */
    val transfers: List<ExpenseMath.Transfer> = emptyList(),
) {
    /** What the screens show as "Net spent": expenses less income, settlements counting for nothing. */
    val totalCents: Long get() = spent.netCents

    val isClosed: Boolean get() = closedAt != null
    val iHaveVoted: Boolean get() = myAccountId != null && myAccountId in closeVotes

    /** One person cannot owe themselves, and with no expenses there is nothing to say either way. */
    val showSettleUp: Boolean get() = balances.size > 1 && rows.isNotEmpty()

    /**
     * Record is offered only where it can succeed (T-165): the list is open, both parties are
     * current members, and neither has agreed to close — the freeze rule would refuse the expense
     * otherwise. Everywhere else the row is display only; on a closed list that is the archive.
     */
    fun canRecord(transfer: ExpenseMath.Transfer): Boolean {
        val current = members.map { it.accountId }.toSet()
        // Reimburse adds an expense, which someone who has agreed to close may not do (T-192).
        return !isClosed && !iHaveVoted &&
            transfer.from in current && transfer.to in current &&
            transfer.from !in closeVotes && transfer.to !in closeVotes
    }
}

/**
 * The expenses of one list, plus what they add up to (T-154).
 *
 * The roster comes off the synced list row rather than the members endpoint, so this screen and
 * its form work offline — that is the whole reason T-152 put it there.
 */
@HiltViewModel
class ExpenseListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    private val listAccounts: ListAccounts,
    private val syncer: Syncer,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ExpenseListUiState())
    val uiState: StateFlow<ExpenseListUiState> = _uiState.asStateFlow()

    /**
     * Agree to close, or withdraw. Online-only, like leaving: the server decides whether this was
     * the last vote needed, which is not a call a device can make for itself while offline. The
     * new state arrives through the ordinary sync that follows.
     */
    fun toggleCloseVote(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isVoting = true, voteError = null) }
        val voted = _uiState.value.iHaveVoted
        try {
            val serverId = listsRepo.serverIdOf(listId)
            val api = listAccounts.api(listId)
            if (serverId == null || api == null) {
                // A list this phone no longer holds has no vote left to cast.
                _uiState.update { it.copy(voteError = UiText.res(R.string.expense_vote_failed)) }
                return@launch
            }
            if (voted) api.withdrawCloseVote(serverId) else api.castCloseVote(serverId)
            syncer.syncNow(emptyList())
        } catch (e: ApiException) {
            // A server refusal — 409 list_closed, 403 not_a_member, 409 not_an_expenses_list — has
            // a specific reason (T-264); ApiException must be caught before IOException, which it
            // extends, or every one of these shows as "couldn't reach the server" instead.
            _uiState.update { it.copy(voteError = ErrorText.of(e, R.string.expense_vote_failed)) }
        } catch (e: IOException) {
            _uiState.update { it.copy(voteError = UiText.res(R.string.error_offline_retry)) }
        } finally {
            _uiState.update { it.copy(isVoting = false) }
        }
    }

    /**
     * Re-syncs every [LIVE_SYNC_INTERVAL_MS] while collected — the screen collects it only while
     * resumed, as ListScreen does (T-177). A failed round is dropped; the next one tries again.
     */
    suspend fun liveSyncLoop() {
        while (true) {
            delay(LIVE_SYNC_INTERVAL_MS)
            runCatching { syncer.syncNow(emptyList()) }
        }
    }

    /** Pull-to-refresh (T-167): an immediate foreground sync with a visible spinner, as ListScreen's. */
    fun refresh(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isRefreshing = true) }
        try {
            syncer.syncNow(emptyList())
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    init {
        viewModelScope.launch {
            // Who "I" am on this list is its account's server-side id, not any other account's.
            val me = listAccounts.accountOf(listId)?.accountId
            _uiState.update { it.copy(myAccountId = me) }
        }
        viewModelScope.launch {
            combine(listsRepo.observeById(listId), itemsRepo.itemsForList(listId), ::Pair)
                .collect { (list, items) ->
                    val members = list?.let { listsRepo.decodeMembers(it.membersJson) } ?: emptyList()
                    val rows = expenseRowsOf(items, itemsRepo)
                    val expenses = rows.map { it.expense }
                    val balances = ExpenseMath.balancesFor(expenses, members.map { m -> m.accountId })
                    _uiState.update {
                        it.copy(
                            currency = list?.currency?.value.orEmpty(),
                            closeVotes = list?.let { row -> listsRepo.decodeCloseVotes(row.closeVotesJson) }
                                ?: emptyList(),
                            closedAt = list?.closedAt,
                            members = members,
                            rows = rows,
                            spent = ExpenseMath.spentTotals(expenses),
                            balances = balances,
                            transfers = ExpenseMath.settle(balances),
                            formerMemberNumbers = ExpenseMath.formerMemberNumbers(
                                expenses,
                                members.map { m -> m.accountId }.toSet(),
                            ),
                        )
                    }
                }
        }
    }
}
