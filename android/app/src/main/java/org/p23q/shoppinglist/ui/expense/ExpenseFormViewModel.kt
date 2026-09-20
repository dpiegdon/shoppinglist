package org.p23q.shoppinglist.ui.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.Expense
import org.p23q.shoppinglist.data.ExpenseMath
import org.p23q.shoppinglist.data.ExpenseType
import org.p23q.shoppinglist.data.ListMember
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import java.time.LocalDate
import javax.inject.Inject

/** Which of an expense's two distributions a field belongs to. */
enum class Side { PAID_BY, PAID_FOR }

/**
 * Why a participant's amounts may not move (T-203), because the row says so: the same lock is
 * reached by agreeing to close and by leaving the list, and "agreed to close" is simply untrue of
 * someone who left.
 */
enum class FrozenReason { NONE, VOTER, FORMER }

/** One participant's row in a distribution. [text] empty means auto: share whatever is left. */
data class ShareRow(
    val accountId: String,
    /** Null for someone who is no longer a member — the screen labels them by [formerNumber]. */
    val email: String?,
    val formerNumber: Int,
    val selected: Boolean,
    val text: String,
    /** The share this participant would get as things stand, for the placeholder. */
    val derivedCents: Long,
    /**
     * Whether their amounts may not move, and why (T-157, T-203): they have agreed to close the
     * list, or have left it. The row still shows what they paid or owe — it is part of the
     * record — but nothing about it can be edited, and it never absorbs a change elsewhere.
     */
    val frozen: FrozenReason = FrozenReason.NONE,
) {
    val isFrozen: Boolean get() = frozen != FrozenReason.NONE
}

data class ExpenseFormUiState(
    val isEditMode: Boolean = false,
    val itemId: String? = null,
    val currency: String = "",
    val name: String = "",
    val nameError: Boolean = false,
    val totalText: String = "",
    /** [totalText] in cents, or zero when it is empty or not a number yet. */
    val totalCents: Long = 0,
    val date: String = "",
    val note: String = "",
    /** Which of the three this entry is (T-245). A new one starts as an ordinary expense. */
    val type: ExpenseType = ExpenseType.EXPENSE,
    /**
     * False on a list with fewer than two people whose amounts can move: there is nobody to pay,
     * so Transfer is not on offer. An entry that already is one keeps the choice whatever the
     * roster now looks like, so it can still be read and retyped.
     */
    val canTransfer: Boolean = true,
    /** A transfer's single sender and single recipient; empty for the other two types. */
    val transferFrom: String = "",
    val transferTo: String = "",
    /** Everyone the two pickers may offer, labelled as the share rows label them. */
    val participants: List<ShareRow> = emptyList(),
    val paidBy: List<ShareRow> = emptyList(),
    val paidFor: List<ShareRow> = emptyList(),
    val paidByError: ExpenseMath.DistributeError? = null,
    val paidForError: ExpenseMath.DistributeError? = null,
    /** Sum of the typed amounts on a side, for the "set the total to X" offer. */
    val paidBySumCents: Long = 0,
    val paidForSumCents: Long = 0,
    /** One participant: nothing to distribute, so the form hides both sections (T-155). */
    val soloList: Boolean = false,
    val isDeleteConfirmOpen: Boolean = false,
    /**
     * The server refused this row's last push and it is parked on the device (T-200), with the
     * code it answered and the participant it named where it named one. The form says so at the
     * top: without it the only signal was a count in the sync status bar, on another screen.
     */
    val isBlocked: Boolean = false,
    val blockedCode: String? = null,
    val blockedAccountId: String? = null,
    /**
     * False when deleting would take a frozen participant's amounts to zero, which the freeze
     * forbids (T-157) — the form says so instead of letting the server refuse it (T-193).
     */
    val canDelete: Boolean = true,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
) {
    /** Whether a transfer names two different people, the only shape the server takes (T-245). */
    val isTransferValid: Boolean
        get() = transferFrom.isNotEmpty() && transferTo.isNotEmpty() && transferFrom != transferTo

    /** Set only while a transfer points both ends at the same person, which is what the form says. */
    val sameMemberError: Boolean
        get() = type == ExpenseType.TRANSFER && transferFrom.isNotEmpty() && transferFrom == transferTo

    val canSave: Boolean
        get() {
            // An expense still needs a title; an income or a transfer falls back to its own name,
            // because "Income" is all there is to say about most refunds (T-245).
            val titleOk = type != ExpenseType.EXPENSE || name.isNotBlank()
            val sharesOk = if (type == ExpenseType.TRANSFER) {
                totalCents > 0 && isTransferValid
            } else {
                paidByError == null && paidForError == null
            }
            return titleOk && sharesOk
        }
}

/**
 * What Reimburse on the balances screen hands to the form (T-165). Everything in it stays editable —
 * changing the total is how a partial settlement is recorded.
 */
data class ExpensePrefill(val name: String, val expense: Expense)

/**
 * The ledger entry form (T-154, T-245). The total drives: typed shares stay as typed, and
 * everything still on auto absorbs the difference. The arithmetic itself is [ExpenseMath], shared
 * with the web client through one case table; this only holds what the user has typed so far,
 * including which of the three an entry is and what switching between them does.
 */
@HiltViewModel
class ExpenseFormViewModel @Inject constructor(
    private val itemsRepo: ItemsRepo,
    private val listsRepo: ListsRepo,
    private val sessionState: SessionState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpenseFormUiState())
    val uiState: StateFlow<ExpenseFormUiState> = _uiState.asStateFlow()

    private var listId: String = ""
    private var members: List<ListMember> = emptyList()
    private var participantIds: List<String> = emptyList()
    private var selected = mapOf(Side.PAID_BY to emptySet<String>(), Side.PAID_FOR to emptySet())
    private var typed = mapOf(Side.PAID_BY to emptyMap<String, String>(), Side.PAID_FOR to emptyMap())
    /** The expense as loaded, so an edit only writes what actually changed (T-88). */
    private var frozenIds: Set<String> = emptySet()
    /** Numbering for participants who have left, taken across the whole list (T-197). */
    private var formerNumbers: Map<String, Int> = emptyMap()
    private var loaded: Expense? = null
    private var loadedName: String = ""
    private var loadedNote: String? = null

    fun startAdd(listId: String, prefill: ExpensePrefill? = null): Job = viewModelScope.launch {
        this@ExpenseFormViewModel.listId = listId
        loadList()
        loaded = null
        if (prefill != null) {
            val expense = prefill.expense
            participantIds = (participantIds + expense.paidBy.keys + expense.paidFor.keys).distinct()
            selected = mapOf(
                Side.PAID_BY to expense.paidBy.keys.toSet(),
                Side.PAID_FOR to expense.paidFor.keys.toSet(),
            )
            // The same reading startEdit gives a stored expense: an equal split comes back as auto,
            // anything else as typed. A settlement is the equal split of one person on each side,
            // which is what lets a changed total follow through — that is a partial settlement.
            typed = mapOf(
                Side.PAID_BY to (if (expense.equalBy) emptyMap() else expense.paidBy),
                Side.PAID_FOR to (if (expense.equalFor) emptyMap() else expense.paidFor),
            )
            _uiState.update {
                ExpenseFormUiState(
                    isEditMode = false,
                    currency = it.currency,
                    name = prefill.name,
                    date = expense.date,
                    totalText = ExpenseMath.fromCents(ExpenseMath.expenseTotalCents(expense)),
                    type = ExpenseMath.entryType(expense),
                    transferFrom = transferEnd(expense, expense.paidBy),
                    transferTo = transferEnd(expense, expense.paidFor),
                )
            }
        } else {
            val me = sessionState.accountId
            selected = mapOf(
                // Whoever is adding it paid, unless they are not on the list at all (they always are).
                Side.PAID_BY to setOfNotNull(me?.takeIf { it in participantIds && !isFrozen(it) }),
                // A new expense can never involve a frozen participant: that would be a change from
                // zero for them, which the server refuses.
                Side.PAID_FOR to participantIds.filterNot(::isFrozen).toSet(),
            )
            typed = mapOf(Side.PAID_BY to emptyMap(), Side.PAID_FOR to emptyMap())
            _uiState.update {
                ExpenseFormUiState(
                    isEditMode = false,
                    currency = it.currency,
                    date = LocalDate.now().toString(),
                )
            }
        }
        recompute()
    }

    /** The one account named on a side of a stored transfer, or "" for anything else (T-245). */
    private fun transferEnd(expense: Expense, shares: Map<String, String>): String =
        if (ExpenseMath.entryType(expense) == ExpenseType.TRANSFER) {
            shares.keys.singleOrNull().orEmpty()
        } else {
            ""
        }

    fun startEdit(itemId: String): Job = viewModelScope.launch {
        val item = itemsRepo.getById(itemId) ?: return@launch
        listId = item.listId
        loadList()
        val expense = itemsRepo.decodeExpense(item.expense.value) ?: return@launch
        loaded = expense
        loadedName = item.name.value
        loadedNote = item.note.value
        // Anyone on this expense who has since left stays visible and editable — their amount is
        // part of the record, and hiding them would silently drop it on the next save.
        participantIds = (participantIds + expense.paidBy.keys + expense.paidFor.keys).distinct()
        selected = mapOf(
            Side.PAID_BY to expense.paidBy.keys.toSet(),
            Side.PAID_FOR to expense.paidFor.keys.toSet(),
        )
        // An equal split comes back as auto so a corrected total redistributes; anything else was
        // meant literally and comes back fixed. A frozen participant's share is always seeded from
        // what is stored, whichever it was: it may not move, so it can never be one of the auto
        // shares — and seeding it is what keeps it at its own amount rather than at nothing.
        typed = mapOf(
            Side.PAID_BY to (if (expense.equalBy) emptyMap() else expense.paidBy) + frozenShares(expense.paidBy),
            Side.PAID_FOR to (if (expense.equalFor) emptyMap() else expense.paidFor) + frozenShares(expense.paidFor),
        )
        _uiState.update {
            it.copy(
                isEditMode = true,
                itemId = itemId,
                canDelete = (expense.paidBy.keys + expense.paidFor.keys).none(::isFrozen),
                isBlocked = item.syncBlocked,
                blockedCode = item.syncBlockedCode,
                blockedAccountId = item.syncBlockedAccountId,
                name = item.name.value,
                note = item.note.value.orEmpty(),
                date = expense.date,
                totalText = ExpenseMath.fromCents(ExpenseMath.expenseTotalCents(expense)),
                // An entry stored before types existed opens as the Expense it has always been,
                // and can be corrected here — that is how a "Settlement" someone recorded as an
                // expense becomes a Transfer (T-245).
                type = ExpenseMath.entryType(expense),
                transferFrom = transferEnd(expense, expense.paidBy),
                transferTo = transferEnd(expense, expense.paidFor),
                isSaved = false,
                isDeleted = false,
            )
        }
        recompute()
    }

    private suspend fun loadList() {
        val list = listsRepo.observeById(listId).first()
        members = list?.let { listsRepo.decodeMembers(it.membersJson) } ?: emptyList()
        participantIds = members.map { it.accountId }
        frozenIds = list?.let { listsRepo.decodeCloseVotes(it.closeVotesJson) }?.toSet() ?: emptySet()
        // Across the list's expenses in the order the list shows them, which is how the list and
        // balances screens number them (T-197): numbering only the expense being edited would call
        // the same person something else here. Read once rather than observed: the numbering is
        // fixed when the form opens, as everything else in it is.
        formerNumbers = ExpenseMath.formerMemberNumbers(
            expenseRowsOf(itemsRepo.activeItemsForListOnce(listId), itemsRepo).map { it.expense },
            members.map { it.accountId }.toSet(),
        )
        _uiState.update { it.copy(currency = list?.currency?.value.orEmpty()) }
    }

    /** Voters, plus anyone on this expense who is no longer a member (T-157). */
    private fun isFrozen(accountId: String): Boolean = frozenReason(accountId) != FrozenReason.NONE

    /** Which of the two put the lock there (T-203), for the row to say. A voter who has also left
     *  is named as a voter: that is the choice they made, and the one they can still withdraw. */
    private fun frozenReason(accountId: String): FrozenReason = when {
        accountId in frozenIds -> FrozenReason.VOTER
        members.none { it.accountId == accountId } -> FrozenReason.FORMER
        else -> FrozenReason.NONE
    }

    private fun frozenShares(stored: Map<String, String>): Map<String, String> =
        stored.filterKeys(::isFrozen)

    /** Whose amounts may move, and so who a transfer may name (T-245). */
    private fun transferCandidates(): List<String> = participantIds.filterNot(::isFrozen)

    /**
     * Changing the type keeps everything the types have in common — title, date, note, total — and
     * translates what they do not (T-245).
     *
     * Expense and Income are the same form read two ways, so their maps survive untouched. A
     * transfer has no maps to keep: it becomes the one sender and one recipient the maps already
     * described when they described exactly one of each, and otherwise starts from me and the
     * first other person. Coming back the other way, the sender becomes the sole payer and the
     * recipient the sole beneficiary, each an equal share of one.
     */
    fun onTypeChange(next: ExpenseType) {
        val current = _uiState.value.type
        if (next == current) return
        val candidates = transferCandidates()
        if (next == ExpenseType.TRANSFER) {
            val payers = participantIds.filter { it in selected[Side.PAID_BY].orEmpty() }
            val beneficiaries = participantIds.filter { it in selected[Side.PAID_FOR].orEmpty() }
            val from: String
            val to: String
            if (payers.size == 1 && beneficiaries.size == 1 && payers[0] != beneficiaries[0]) {
                from = payers[0]
                to = beneficiaries[0]
            } else {
                val me = sessionState.accountId
                from = if (me != null && me in candidates) {
                    me
                } else {
                    payers.firstOrNull { it in candidates } ?: candidates.firstOrNull().orEmpty()
                }
                // The beneficiaries say more about what the user meant than the roster does, so
                // they are asked first; the first other member is the fallback.
                to = beneficiaries.firstOrNull { it != from && it in candidates }
                    ?: candidates.firstOrNull { it != from }.orEmpty()
            }
            _uiState.update { it.copy(type = next, transferFrom = from, transferTo = to) }
        } else {
            if (current == ExpenseType.TRANSFER) {
                val state = _uiState.value
                selected = mapOf(
                    Side.PAID_BY to setOfNotNull(state.transferFrom.ifEmpty { null }),
                    Side.PAID_FOR to setOfNotNull(state.transferTo.ifEmpty { null }),
                )
                typed = mapOf(Side.PAID_BY to emptyMap(), Side.PAID_FOR to emptyMap())
            }
            _uiState.update { it.copy(type = next) }
        }
        recompute()
    }

    fun onTransferFromChange(accountId: String) {
        _uiState.update { it.copy(transferFrom = accountId) }
    }

    fun onTransferToChange(accountId: String) {
        _uiState.update { it.copy(transferTo = accountId) }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, nameError = false) }

    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    fun onDateChange(value: String) = _uiState.update { it.copy(date = value) }

    fun onTotalChange(value: String) {
        _uiState.update { it.copy(totalText = value) }
        recompute()
    }

    fun toggleParticipant(side: Side, accountId: String) {
        if (isFrozen(accountId)) return
        val current = selected[side].orEmpty()
        val next = if (accountId in current) current - accountId else current + accountId
        selected = selected + (side to next)
        // Deselecting drops whatever was typed for them, so re-selecting starts from an equal
        // share rather than a stale number.
        if (accountId !in next) typed = typed + (side to (typed[side].orEmpty() - accountId))
        recompute()
    }

    fun onShareChange(side: Side, accountId: String, value: String) {
        if (isFrozen(accountId)) return
        typed = typed + (side to (typed[side].orEmpty() + (accountId to value)))
        recompute()
    }

    /** Take the typed amounts at face value and move the total to their sum instead. */
    fun useSumAsTotal(side: Side) {
        val sum = if (side == Side.PAID_BY) _uiState.value.paidBySumCents else _uiState.value.paidForSumCents
        onTotalChange(ExpenseMath.fromCents(sum))
    }

    private fun entries(side: Side): List<ExpenseMath.ShareEntry> =
        participantIds.filter { it in selected[side].orEmpty() }.map { id ->
            val text = typed[side].orEmpty()[id].orEmpty().trim()
            // A frozen share is seeded from the stored value in startEdit, so it arrives here as
            // typed text and is fixed by that alone — never auto, never absorbing a change.
            ExpenseMath.ShareEntry(id, if (text.isEmpty()) null else ExpenseMath.toCents(text))
        }

    private fun recompute() {
        val totalCents = ExpenseMath.toCents(_uiState.value.totalText.trim().ifEmpty { "0" }) ?: 0
        val results = Side.entries.associateWith { ExpenseMath.distribute(totalCents, entries(it)) }
        _uiState.update { state ->
            state.copy(
                soloList = participantIds.size <= 1,
                totalCents = totalCents,
                // A list of one has nobody to pay; an entry that already is a transfer keeps the
                // choice so it can still be read and retyped (T-245).
                canTransfer = transferCandidates().size >= 2 || state.type == ExpenseType.TRANSFER,
                participants = participantRows(),
                paidBy = rowsFor(Side.PAID_BY, results.getValue(Side.PAID_BY)),
                paidFor = rowsFor(Side.PAID_FOR, results.getValue(Side.PAID_FOR)),
                paidByError = (results.getValue(Side.PAID_BY) as? ExpenseMath.DistributeResult.Failed)?.error,
                paidForError = (results.getValue(Side.PAID_FOR) as? ExpenseMath.DistributeResult.Failed)?.error,
                paidBySumCents = entries(Side.PAID_BY).sumOf { it.fixed ?: 0 },
                paidForSumCents = entries(Side.PAID_FOR).sumOf { it.fixed ?: 0 },
            )
        }
    }

    /**
     * Everyone the transfer pickers may offer, labelled exactly as the share rows label them
     * (T-197): an email while they are a member, a number once they have left. Neither side of a
     * distribution, so nothing here is selected or typed.
     */
    private fun participantRows(): List<ShareRow> = participantIds.map { id ->
        ShareRow(
            accountId = id,
            email = members.firstOrNull { it.accountId == id }?.email,
            formerNumber = formerNumbers[id] ?: 0,
            selected = false,
            text = "",
            derivedCents = 0,
            frozen = frozenReason(id),
        )
    }

    private fun rowsFor(side: Side, result: ExpenseMath.DistributeResult): List<ShareRow> {
        val shares = (result as? ExpenseMath.DistributeResult.Shares)?.shares.orEmpty()
        return participantIds.map { id ->
            ShareRow(
                accountId = id,
                // An email for a member; a departed participant is only an id, so the screen
                // labels them by number — that string needs a Context, which does not belong here.
                email = members.firstOrNull { it.accountId == id }?.email,
                formerNumber = formerNumbers[id] ?: 0,
                selected = id in selected[side].orEmpty(),
                text = typed[side].orEmpty()[id].orEmpty(),
                derivedCents = shares[id] ?: 0,
                frozen = frozenReason(id),
            )
        }
    }

    /**
     * Returns null when the form is not saveable, so the dialog stays open.
     *
     * [typeLabel] is the current type's own localized name — "Income", "Transfer" — which is what
     * an income or a transfer left untitled falls back to (T-245); the screen holds the string
     * resources, this does not. An expense ignores it and still insists on a title.
     */
    fun save(typeLabel: String = ""): Job? {
        val state = _uiState.value
        val title = state.name.trim().ifBlank {
            if (state.type == ExpenseType.EXPENSE) "" else typeLabel.trim()
        }
        if (title.isBlank()) {
            _uiState.update { it.copy(nameError = true) }
            return null
        }
        val expense = if (state.type == ExpenseType.TRANSFER) {
            if (!state.isTransferValid || state.totalCents <= 0) return null
            val amount = ExpenseMath.fromCents(state.totalCents)
            // One sender, one recipient, the same amount on both sides: the total is the whole of
            // it, so a partial settlement is recorded by editing the total.
            Expense(
                paidBy = mapOf(state.transferFrom to amount),
                equalBy = true,
                paidFor = mapOf(state.transferTo to amount),
                equalFor = true,
                date = state.date,
                type = ExpenseType.TRANSFER.wire,
            )
        } else {
            val byResult = ExpenseMath.distribute(totalCents(), entries(Side.PAID_BY))
            val forResult = ExpenseMath.distribute(totalCents(), entries(Side.PAID_FOR))
            if (byResult !is ExpenseMath.DistributeResult.Shares) return null
            if (forResult !is ExpenseMath.DistributeResult.Shares) return null
            Expense(
                paidBy = ExpenseMath.sharesToWire(byResult.shares),
                // "Everything selected is on auto" is exactly what makes a later total change
                // redistribute instead of refusing.
                equalBy = entries(Side.PAID_BY).all { it.fixed == null },
                paidFor = ExpenseMath.sharesToWire(forResult.shares),
                equalFor = entries(Side.PAID_FOR).all { it.fixed == null },
                date = state.date,
                type = state.type.wire,
            )
        }
        val note = state.note.trim().ifBlank { null }

        return viewModelScope.launch {
            val itemId = state.itemId
            if (itemId == null) {
                itemsRepo.createExpense(listId, title, expense, note)
            } else {
                // Change-scoped (T-88): an untouched field keeps its clock and cannot revert a
                // collaborator's concurrent edit to it.
                if (title != loadedName) itemsRepo.rename(itemId, title)
                if (note != loadedNote) itemsRepo.setNote(itemId, note)
                if (!isUnchanged(expense)) itemsRepo.setExpense(itemId, expense)
            }
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    private fun totalCents(): Long =
        ExpenseMath.toCents(_uiState.value.totalText.trim().ifEmpty { "0" }) ?: 0

    /**
     * Whether saving would change the stored entry at all (T-88).
     *
     * Spelling out the type an entry already had is not a change: an absent type IS `expense`
     * (T-245), so an entry written before types existed and opened without being touched must
     * still write nothing — re-stamping its clock would let it revert a collaborator's edit.
     */
    private fun isUnchanged(expense: Expense): Boolean {
        val stored = loaded ?: return false
        if (expense == stored) return true
        return stored.type == null && expense == stored.copy(type = ExpenseType.EXPENSE.wire)
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
