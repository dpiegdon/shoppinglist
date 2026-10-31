package org.p23q.shoppinglist.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ListGuest
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.JoinedLists
import org.p23q.shoppinglist.data.ListAccounts
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.error_offline_retry
import org.p23q.shoppinglist.resources.expense_vote_failed
import org.p23q.shoppinglist.resources.guest_msg_failed
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import org.p23q.shoppinglist.ui.list.LIVE_SYNC_INTERVAL_MS
import kotlinx.io.IOException

/**
 * One expense, with its money tuple already decoded for rendering: [expense] as stored, whose keys
 * the row names, and [folded] with every claimed guest replaced by its claimant (T-364), which is
 * what every figure is computed on.
 */
data class ExpenseRow(val item: ItemEntity, val expense: Expense, val folded: Expense = expense)

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
    /** The ledger's guests (T-364), claimed or not, as the list row last had them. */
    val guests: List<ListGuest> = emptyList(),
    /** Whether the account's server keeps guests (T-364): the chooser and claiming need it. */
    val guestsEnabled: Boolean = false,
    /** "Are you one of these?" is open, right after joining (T-364). */
    val isChooserOpen: Boolean = false,
    val isClaiming: Boolean = false,
    val chooserError: UiText? = null,
    /** Newest date first; within a date, newest first. */
    val rows: List<ExpenseRow> = emptyList(),
    /** What the ledger spent, took in and the net of the two (T-245). */
    val spent: ExpenseMath.SpentTotals = ExpenseMath.SpentTotals(0, 0, 0),
    val balances: List<ExpenseMath.Balance> = emptyList(),
    /** Numbering for participants who are no longer members (T-152), over effective ids (T-364). */
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

    /** Who is who: labels, guests and the freeze, one source for every row (T-364). */
    val roster: Roster get() = Roster(members, guests, closeVotes, formerMemberNumbers)

    /** The guests the chooser offers: every one nobody has claimed yet. */
    val unclaimedGuests: List<ListGuest> get() = guests.filter { !it.isClaimed }

    val isClosed: Boolean get() = closedAt != null
    val iHaveVoted: Boolean get() = myAccountId != null && myAccountId in closeVotes

    /** One person cannot owe themselves, and with no expenses there is nothing to say either way. */
    val showSettleUp: Boolean get() = balances.size > 1 && rows.isNotEmpty()

    /**
     * Record is offered only where it can succeed (T-165): the list is open, both parties are
     * current members or unclaimed guests (T-364), and neither is frozen — has agreed to close,
     * or is a locked guest — which the freeze rule would refuse. Everywhere else the row is
     * display only; on a closed list that is the archive.
     */
    fun canRecord(transfer: ExpenseMath.Transfer): Boolean {
        val roster = roster
        val current = roster.participantIds.toSet()
        // Reimburse adds an expense, which someone who has agreed to close may not do (T-192).
        return !isClosed && !iHaveVoted &&
            transfer.from in current && transfer.to in current &&
            !roster.isFrozen(transfer.from) && !roster.isFrozen(transfer.to)
    }
}

/**
 * The expenses of one list, plus what they add up to (T-154).
 *
 * The roster comes off the synced list row rather than the members endpoint, so this screen and
 * its form work offline — that is the whole reason T-152 put it there.
 */
class ExpenseListViewModel(
    savedStateHandle: SavedStateHandle,
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    private val listAccounts: ListAccounts,
    private val syncer: Syncer,
    private val joinedLists: JoinedLists = JoinedLists(),
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
                _uiState.update { it.copy(voteError = UiText.res(Res.string.expense_vote_failed)) }
                return@launch
            }
            if (voted) api.withdrawCloseVote(serverId) else api.castCloseVote(serverId)
            syncer.syncNow(emptyList())
        } catch (e: ApiException) {
            // A server refusal — 409 list_closed, 403 not_a_member, 409 not_an_expenses_list — has
            // a specific reason (T-264); ApiException must be caught before IOException, which it
            // extends, or every one of these shows as "couldn't reach the server" instead.
            _uiState.update { it.copy(voteError = ErrorText.of(e, Res.string.expense_vote_failed)) }
        } catch (e: IOException) {
            _uiState.update { it.copy(voteError = UiText.res(Res.string.error_offline_retry)) }
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

    /**
     * The chooser after a join (T-364): claim [guestId] as me. Online-only, like a vote; the claim
     * reaches the figures through the sync that follows. The chooser closes once it went through.
     */
    fun claimFromChooser(guestId: String): Job = viewModelScope.launch {
        _uiState.update { it.copy(isClaiming = true, chooserError = null) }
        try {
            val serverId = listsRepo.serverIdOf(listId)
            val api = listAccounts.api(listId)
            if (serverId == null || api == null) {
                _uiState.update { it.copy(chooserError = UiText.res(Res.string.guest_msg_failed)) }
                return@launch
            }
            val claimed = api.claimGuest(serverId, guestId)
            _uiState.update { state ->
                state.copy(isChooserOpen = false, guests = state.guests.map { if (it.id == claimed.id) claimed else it })
            }
            runCatching { syncer.syncNow(emptyList()) }
        } catch (e: ApiException) {
            val message = if (e.code == "participant_frozen") {
                // A locked guest's numbers may not move onto anyone (T-364).
                val name = _uiState.value.guests.firstOrNull { it.id == guestId }?.name ?: guestId
                ErrorText.frozen(name, guest = true)
            } else {
                ErrorText.of(e, Res.string.guest_msg_failed)
            }
            _uiState.update { it.copy(chooserError = message) }
        } catch (e: IOException) {
            _uiState.update { it.copy(chooserError = UiText.res(Res.string.error_offline_retry)) }
        } finally {
            _uiState.update { it.copy(isClaiming = false) }
        }
    }

    /** "No, I'm new": final for this join; the list menu's "This is me" covers second thoughts. */
    fun dismissChooser() = _uiState.update { it.copy(isChooserOpen = false, chooserError = null) }

    init {
        viewModelScope.launch {
            // Who "I" am on this list is its account's server-side id, not any other account's.
            val account = listAccounts.accountOf(listId)
            val guestsEnabled = account?.supportsGuests == true
            _uiState.update { it.copy(myAccountId = account?.accountId, guestsEnabled = guestsEnabled) }
            // Taken once, whatever the list turns out to hold: the chooser belongs to this join.
            var askWhoIAm = joinedLists.consume(listId)
            combine(listsRepo.observeById(listId), itemsRepo.itemsForList(listId), ::Pair)
                .collect { (list, items) ->
                    val roster = Roster(
                        members = list?.let { listsRepo.decodeMembers(it.membersJson) } ?: emptyList(),
                        guests = list?.let { listsRepo.decodeGuests(it.guestsJson) } ?: emptyList(),
                        closeVotes = list?.let { row -> listsRepo.decodeCloseVotes(row.closeVotesJson) } ?: emptyList(),
                    )
                    // Every figure is computed on folded entries (T-364): a claimed guest's
                    // amounts are its claimant's. The rows still name what is stored.
                    val rows = expenseRowsOf(items, itemsRepo).map { it.copy(folded = ExpenseMath.fold(it.expense, roster.aliases)) }
                    val folded = rows.map { it.folded }
                    val balances = ExpenseMath.balancesFor(folded, roster.participantIds)
                    val openChooser = askWhoIAm && list != null && guestsEnabled &&
                        ListKind.isExpenses(list.kind.value) && list.closedAt == null &&
                        roster.unclaimedGuests.isNotEmpty()
                    if (list != null) askWhoIAm = false
                    _uiState.update {
                        it.copy(
                            currency = list?.currency?.value.orEmpty(),
                            closeVotes = roster.closeVotes,
                            closedAt = list?.closedAt,
                            members = roster.members,
                            guests = roster.guests,
                            rows = rows,
                            spent = ExpenseMath.spentTotals(folded),
                            balances = balances,
                            transfers = ExpenseMath.settle(balances),
                            formerMemberNumbers = Roster.formerNumbersFor(rows.map { row -> row.expense }, roster),
                            isChooserOpen = it.isChooserOpen || openChooser,
                        )
                    }
                }
        }
    }
}
