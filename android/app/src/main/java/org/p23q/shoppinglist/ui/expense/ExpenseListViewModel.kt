package org.p23q.shoppinglist.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.ui.Routes
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
)

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
    sessionState: SessionState,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ExpenseListUiState(myAccountId = sessionState.accountId))
    val uiState: StateFlow<ExpenseListUiState> = _uiState.asStateFlow()

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
                    _uiState.update {
                        it.copy(
                            currency = list?.currency?.value.orEmpty(),
                            members = members,
                            rows = rows,
                            totalCents = expenses.sumOf(ExpenseMath::expenseTotalCents),
                            balances = ExpenseMath.balancesFor(expenses, members.map { m -> m.accountId }),
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
