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
import org.p23q.shoppinglist.data.ListMember
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import java.time.LocalDate
import javax.inject.Inject

/** Which of an expense's two distributions a field belongs to. */
enum class Side { PAID_BY, PAID_FOR }

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
     * Their amounts may not move (T-157): they have agreed to close the list, or have left it.
     * The row still shows what they paid or owe — it is part of the record — but nothing about it
     * can be edited, and it never absorbs a change elsewhere.
     */
    val frozen: Boolean = false,
)

data class ExpenseFormUiState(
    val isEditMode: Boolean = false,
    val itemId: String? = null,
    val currency: String = "",
    val name: String = "",
    val nameError: Boolean = false,
    val totalText: String = "",
    val date: String = "",
    val note: String = "",
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
     * False when deleting would take a frozen participant's amounts to zero, which the freeze
     * forbids (T-157) — the form says so instead of letting the server refuse it (T-193).
     */
    val canDelete: Boolean = true,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && paidByError == null && paidForError == null
}

/**
 * What Reimburse on the balances screen hands to the form (T-165). Everything in it stays editable —
 * changing the total is how a partial settlement is recorded.
 */
data class ExpensePrefill(val name: String, val expense: Expense)

/**
 * The expense form (T-154). The total drives: typed shares stay as typed, and everything still on
 * auto absorbs the difference. The arithmetic itself is [ExpenseMath], shared with the web client
 * through one case table; this only holds what the user has typed so far.
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
                name = item.name.value,
                note = item.note.value.orEmpty(),
                date = expense.date,
                totalText = ExpenseMath.fromCents(ExpenseMath.expenseTotalCents(expense)),
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
        _uiState.update { it.copy(currency = list?.currency?.value.orEmpty()) }
    }

    /** Voters, plus anyone on this expense who is no longer a member (T-157). */
    private fun isFrozen(accountId: String): Boolean =
        accountId in frozenIds || members.none { it.accountId == accountId }

    private fun frozenShares(stored: Map<String, String>): Map<String, String> =
        stored.filterKeys(::isFrozen)

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
                paidBy = rowsFor(Side.PAID_BY, results.getValue(Side.PAID_BY)),
                paidFor = rowsFor(Side.PAID_FOR, results.getValue(Side.PAID_FOR)),
                paidByError = (results.getValue(Side.PAID_BY) as? ExpenseMath.DistributeResult.Failed)?.error,
                paidForError = (results.getValue(Side.PAID_FOR) as? ExpenseMath.DistributeResult.Failed)?.error,
                paidBySumCents = entries(Side.PAID_BY).sumOf { it.fixed ?: 0 },
                paidForSumCents = entries(Side.PAID_FOR).sumOf { it.fixed ?: 0 },
            )
        }
    }

    private fun rowsFor(side: Side, result: ExpenseMath.DistributeResult): List<ShareRow> {
        val shares = (result as? ExpenseMath.DistributeResult.Shares)?.shares.orEmpty()
        val formerNumbers = ExpenseMath.formerMemberNumbers(
            listOfNotNull(loaded),
            members.map { it.accountId }.toSet(),
        )
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
                frozen = isFrozen(id),
            )
        }
    }

    /** Returns null when the form is not saveable, so the dialog stays open. */
    fun save(): Job? {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = true) }
            return null
        }
        val byResult = ExpenseMath.distribute(totalCents(), entries(Side.PAID_BY))
        val forResult = ExpenseMath.distribute(totalCents(), entries(Side.PAID_FOR))
        if (byResult !is ExpenseMath.DistributeResult.Shares) return null
        if (forResult !is ExpenseMath.DistributeResult.Shares) return null

        val expense = Expense(
            paidBy = ExpenseMath.sharesToWire(byResult.shares),
            // "Everything selected is on auto" is exactly what makes a later total change
            // redistribute instead of refusing.
            equalBy = entries(Side.PAID_BY).all { it.fixed == null },
            paidFor = ExpenseMath.sharesToWire(forResult.shares),
            equalFor = entries(Side.PAID_FOR).all { it.fixed == null },
            date = state.date,
        )
        val name = state.name.trim()
        val note = state.note.trim().ifBlank { null }

        return viewModelScope.launch {
            val itemId = state.itemId
            if (itemId == null) {
                itemsRepo.createExpense(listId, name, expense, note)
            } else {
                // Change-scoped (T-88): an untouched field keeps its clock and cannot revert a
                // collaborator's concurrent edit to it.
                if (name != loadedName) itemsRepo.rename(itemId, name)
                if (note != loadedNote) itemsRepo.setNote(itemId, note)
                if (expense != loaded) itemsRepo.setExpense(itemId, expense)
            }
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    private fun totalCents(): Long =
        ExpenseMath.toCents(_uiState.value.totalText.trim().ifEmpty { "0" }) ?: 0

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
