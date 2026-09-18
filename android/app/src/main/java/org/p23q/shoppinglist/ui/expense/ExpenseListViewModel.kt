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
import org.p23q.shoppinglist.data.Expense
import org.p23q.shoppinglist.data.ExpenseMath
import org.p23q.shoppinglist.data.ListMember
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.list.LIVE_SYNC_INTERVAL_MS
import java.io.IOException
import javax.inject.Inject

/** One expense, with its money tuple already decoded for rendering. */
data class ExpenseRow(val item: ItemEntity, val expense: Expense)

data class ExpenseListUiState(
    val currency: String = "",
    val members: List<ListMember> = emptyList(),
    /** Newest date first; within a date, newest first. */
    val rows: List<ExpenseRow> = emptyList(),
    val totalCents: Long = 0,
    val balances: List<ExpenseMath.Balance> = emptyList(),
    /** Numbering for participants who are no longer members (T-152). */
    val formerMemberNumbers: Map<String, Int> = emptyMap(),
    val myAccountId: String? = null,
    /** Who has agreed to close (T-157), and when it closed — both server-maintained. */
    val closeVotes: List<String> = emptyList(),
    val closedAt: Long? = null,
    val isVoting: Boolean = false,
    val voteError: Boolean = false,
    /** Pull-to-refresh in progress (T-167), as on the other lists. */
    val isRefreshing: Boolean = false,
    /** Who pays whom to zero the balances (T-165), in the order the shared algorithm fixes. */
    val transfers: List<ExpenseMath.Transfer> = emptyList(),
) {
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
    private val apiProvider: ApiProvider,
    private val syncer: Syncer,
    sessionState: SessionState,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ExpenseListUiState(myAccountId = sessionState.accountId))
    val uiState: StateFlow<ExpenseListUiState> = _uiState.asStateFlow()

    /**
     * Agree to close, or withdraw. Online-only, like leaving: the server decides whether this was
     * the last vote needed, which is not a call a device can make for itself while offline. The
     * new state arrives through the ordinary sync that follows.
     */
    fun toggleCloseVote(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isVoting = true, voteError = false) }
        val voted = _uiState.value.iHaveVoted
        try {
            val api = apiProvider.get()
            if (voted) api.withdrawCloseVote(listId) else api.castCloseVote(listId)
            syncer.syncNow(emptyList())
        } catch (e: IOException) {
            _uiState.update { it.copy(voteError = true) }
        } catch (e: IllegalStateException) {
            _uiState.update { it.copy(voteError = true) }
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
            combine(listsRepo.observeById(listId), itemsRepo.itemsForList(listId), ::Pair)
                .collect { (list, items) ->
                    val members = list?.let { listsRepo.decodeMembers(it.membersJson) } ?: emptyList()
                    // Sorted here rather than in SQL: the date lives inside the expense JSON, which
                    // SQLite cannot order by without unpacking it.
                    val rows = items
                        .mapNotNull { item ->
                            itemsRepo.decodeExpense(item.expense.value)?.let { ExpenseRow(item, it) }
                        }
                        .sortedWith(
                            compareByDescending<ExpenseRow> { it.expense.date }
                                .thenByDescending { it.item.createdAt },
                        )
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
                            totalCents = expenses.sumOf(ExpenseMath::expenseTotalCents),
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
