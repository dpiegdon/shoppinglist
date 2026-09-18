package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.Expense
import org.p23q.shoppinglist.data.ExpenseMath
import java.text.DateFormat
import java.util.Date

/**
 * An expenses list (T-154): what was spent, by whom, for whom.
 *
 * None of the shopping apparatus applies — no statuses, categories, backlog, stores or
 * show-checked — so this is a screen of its own rather than a branch inside ListScreen.
 */
@Composable
fun ExpenseListScreen(
    onAddExpense: () -> Unit,
    onEditExpense: (String) -> Unit,
    onOpenBalances: () -> Unit,
    onOpenListProps: () -> Unit,
    viewModel: ExpenseListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val myBalance = state.balances.firstOrNull { it.accountId == state.myAccountId }

    Scaffold(
        floatingActionButton = {
            // A closed list is an archive: nothing to add to it (T-157).
            if (!state.isClosed) {
                FloatingActionButton(onClick = onAddExpense) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.expense_add))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    modifier = Modifier.weight(1f).clickable(onClick = onOpenBalances),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.expense_total_spent),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "${ExpenseMath.fromCents(state.totalCents)} ${state.currency}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        // A list of one is always square with itself, so the balance is noise there.
                        if (state.members.size > 1 && myBalance != null) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    stringResource(R.string.expense_your_balance),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    "${ExpenseMath.fromCents(myBalance.balanceCents)} ${state.currency}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = balanceColor(myBalance.balanceCents),
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onOpenListProps) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.nav_list_properties),
                    )
                }
            }

            CloseVoteBanner(
                state = state,
                onToggleVote = { viewModel.toggleCloseVote() },
            )

            if (state.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.expense_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.item.id }) { row ->
                    // Resolved here rather than passed as lambdas: participantLabel is itself a
                    // composable (it reads string resources), and a non-inline lambda is not a
                    // composable context.
                    ExpenseRowView(
                        row = row,
                        currency = state.currency,
                        paidByLabel = row.expense.paidBy.keys
                            .map { participantLabel(it, state) }
                            .joinToString(", "),
                        forLabel = forWhomLabel(row.expense, state),
                        onClick = if (state.isClosed) null else ({ onEditExpense(row.item.id) }),
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun ExpenseRowView(
    row: ExpenseRow,
    currency: String,
    paidByLabel: String,
    forLabel: String,
    /** Null on a closed list: the row is still there to read, it just cannot be opened. */
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.item.name.value, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.expense_row_by, paidByLabel, forLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(row.expense.date, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "${ExpenseMath.fromCents(ExpenseMath.expenseTotalCents(row.expense))} $currency",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** A member's initials, or a stable "Former member N" for someone who has left (T-152). */
@Composable
internal fun participantLabel(accountId: String, state: ExpenseListUiState): String {
    val member = state.members.firstOrNull { it.accountId == accountId }
    if (member != null) return member.initials
    return stringResource(R.string.expense_former_member, state.formerMemberNumbers[accountId] ?: 0)
}

/** "everyone" beats naming every member — the usual case, and the longest string. */
@Composable
internal fun forWhomLabel(expense: Expense, state: ExpenseListUiState): String {
    val ids = expense.paidFor.keys
    val everyone = state.members.isNotEmpty() &&
        ids.size == state.members.size &&
        state.members.all { it.accountId in ids }
    return if (everyone) {
        stringResource(R.string.expense_for_everyone)
    } else {
        ids.map { participantLabel(it, state) }.joinToString(", ")
    }
}

@Composable
internal fun balanceColor(cents: Long) = when {
    cents < 0 -> MaterialTheme.colorScheme.error
    cents > 0 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Where this list stands on closing (T-158). Silent until someone votes, a running count while
 * votes are pending, and a note once it is closed.
 */
@Composable
private fun CloseVoteBanner(state: ExpenseListUiState, onToggleVote: () -> Unit) {
    if (state.isClosed) {
        Text(
            text = stringResource(
                R.string.expense_closed_on,
                DateFormat.getDateInstance().format(Date(state.closedAt ?: 0L)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    if (state.closeVotes.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.expense_agree_count, state.closeVotes.size, state.members.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.voteError) {
                Text(
                    stringResource(R.string.expense_vote_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = onToggleVote, enabled = !state.isVoting) {
            Text(
                stringResource(
                    if (state.iHaveVoted) R.string.expense_withdraw_vote else R.string.expense_agree_to_close,
                ),
            )
        }
    }
}
