package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.ui.AddFab
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.theme.LocalPositiveBalanceColor

/**
 * A ledger (T-154, T-245): what was spent, taken in and settled — by whom, for whom.
 *
 * None of the shopping apparatus applies — no statuses, categories, backlog, stores or
 * show-checked — so this is a screen of its own rather than a branch inside ListScreen. It still
 * looks like one of the lists (T-168): the same controls row, headings in the same style (dates
 * here, categories there), the same dividers, the same Add button and pull-to-refresh (T-167).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onAddExpense: () -> Unit,
    onEditExpense: (String) -> Unit,
    onOpenListProps: () -> Unit,
    /** Reimburse on a settle-up row (T-165, T-171): the pre-filled expense to open the form with. */
    onReimburse: (ExpensePrefill) -> Unit = {},
    viewModel: ExpenseListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val myBalance = state.balances.firstOrNull { it.accountId == state.myAccountId }
    // Five-second refresh while this list is on screen, as ListScreen does (T-128, T-177): two
    // people splitting a bill at the table see each other's entries without pulling.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.liveSyncLoop()
        }
    }
    // Expenses or balances (T-172). Saveable, so rotating the phone keeps the view you were on.
    var showBalances by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            // A closed list is an archive: nothing to add to it (T-157). Balances has its own actions.
            // Not for someone who has agreed to close (T-192): the server refuses their new expenses.
            if (!state.isClosed && !showBalances && !state.iHaveVoted) {
                AddFab(onClick = onAddExpense, contentDescription = stringResource(R.string.expense_add))
            }
        },
        // Inside AppDrawerScaffold's own Scaffold, which already insets for the top bar — the same
        // reason ListScreen zeroes this, and without it the screen started a status bar too low.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The controls row every list has (T-168), with settings where ListScreen keeps it, and the
            // Expenses | Balances selector where ListScreen has Show checked (T-172). Balances used
            // to be a screen of its own, reached through a summary card nothing marked as a button.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Compact (T-174): less padding than Material's default, so the pill stays small. The
                // check on the active segment stays, as on the web and on Show checked.
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !showBalances,
                        onClick = { showBalances = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text(stringResource(R.string.expense_entries), style = MaterialTheme.typography.labelMedium) }
                    SegmentedButton(
                        selected = showBalances,
                        onClick = { showBalances = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text(stringResource(R.string.expense_balances), style = MaterialTheme.typography.labelMedium) }
                }
                IconButton(onClick = onOpenListProps, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.nav_list_properties),
                    )
                }
            }

            if (showBalances) {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    BalancesContent(state = state, onReimburse = onReimburse)
                }
                return@Column
            }

            // A summary now, not the way into balances: the selector above is (T-172).
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                            AppFormat.money(state.totalCents, state.currency, appLocale()),
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
                                AppFormat.signedMoney(myBalance.balanceCents, state.currency, appLocale()),
                                style = MaterialTheme.typography.titleMedium,
                                color = balanceColor(myBalance.balanceCents),
                            )
                        }
                    }
                }
            }

            CloseVoteBanner(
                state = state,
                onToggleVote = { viewModel.toggleCloseVote() },
            )

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // A LazyColumn even when empty: the pull gesture needs something scrollable to grab.
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.rows.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.expense_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                    // Rows arrive newest date first, so grouping keeps that order.
                    state.rows.groupBy { it.expense.date }.entries.forEachIndexed { index, (date, rows) ->
                        item(key = "date-$date") {
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            // Styled exactly like ListScreen's category headings.
                            Text(
                                // In the app's language, as the closed banner is (T-180).
                                text = AppFormat.calendarDate(date, appLocale()),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                            )
                        }
                        itemsIndexed(rows, key = { _, row -> row.item.localId }) { rowIndex, row ->
                            // Resolved here rather than passed as lambdas: participantLabel is itself a
                            // composable (it reads string resources), and a non-inline lambda is not a
                            // composable context.
                            ExpenseRowView(
                                row = row,
                                currency = state.currency,
                                subLine = subLine(row.expense, state),
                                // What this one entry does to MY balance, the only figure on the
                                // row that is mine rather than the group's (T-245).
                                myEffectCents = state.myAccountId
                                    ?.let { ExpenseMath.entryEffectCents(row.expense, it) }
                                    ?: 0L,
                                refusal = refusalOf(row, state),
                                // Nothing to open on a closed list, nor for someone who has agreed to close (T-193).
                                onClick = if (state.isClosed || state.iHaveVoted) null else ({ onEditExpense(row.item.localId) }),
                            )
                            if (rowIndex < rows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                    // Room below the last row, so the floating Add button never covers it.
                    item(key = "fab-clearance") { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

/** The "not saved" mark on a parked row, and the reason where the refusal gave one (T-200). */
internal data class RowRefusal(val label: String, val reason: String?)

/**
 * Why the server refused this expense's last push, or null when nothing of it is parked (T-200).
 * The sync status bar counts the rows needing attention; only the row itself can say which one and
 * what went wrong, and the user is never watching when the push queue empties.
 */
@Composable
internal fun refusalOf(row: ExpenseRow, state: ExpenseListUiState): RowRefusal? {
    if (!row.item.syncBlocked) return null
    val who = row.item.syncBlockedAccountId?.let { participantLabel(it, state) }
    return RowRefusal(
        label = stringResource(R.string.expense_not_saved),
        reason = ErrorText.refusal(row.item.syncBlockedCode, who)?.asString(),
    )
}

@Composable
private fun ExpenseRowView(
    row: ExpenseRow,
    currency: String,
    /** Who the entry moved money between, said the way its type reads it (T-245). */
    subLine: String,
    /** What this entry does to the signed-in account's balance; zero shows nothing. */
    myEffectCents: Long,
    /** Set when the server refused this row's last push and the device parked it (T-200). */
    refusal: RowRefusal?,
    /** Null on a closed list: the row is still there to read, it just cannot be opened. */
    onClick: (() -> Unit)?,
) {
    val type = ExpenseMath.entryType(row.expense)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No date line: the heading above the group already says it.
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.item.name.value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // An expense is the ordinary case and says nothing; the other two say what they
                // are, quietly, beside the title (T-245).
                if (type != ExpenseType.EXPENSE) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(typeLabelOf(type)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(subLine, style = MaterialTheme.typography.bodySmall)
            if (refusal != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The icon carries the "not saved" half for anyone who cannot see the colour,
                    // which leaves the line itself for the reason.
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = refusal.label,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        refusal.reason ?: refusal.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val totalCents = ExpenseMath.expenseTotalCents(row.expense)
            // Never coloured: what the group laid out or took in is not a position anyone is up or
            // down. Income is prefixed "+", because money coming in reads the other way (T-245).
            Text(
                if (type == ExpenseType.INCOME) {
                    AppFormat.signedMoney(totalCents, currency, appLocale())
                } else {
                    AppFormat.money(totalCents, currency, appLocale())
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (myEffectCents != 0L) {
                Text(
                    AppFormat.signedMoney(myEffectCents, currency, appLocale()),
                    style = MaterialTheme.typography.bodySmall,
                    color = balanceColor(myEffectCents),
                )
            }
        }
    }
}

/** A member's initials, or a stable "Former member N" for someone who has left (T-152). */
@Composable
internal fun participantLabel(accountId: String, state: ExpenseListUiState): String {
    val member = state.members.firstOrNull { it.accountId == accountId }
    if (member != null) return member.initials
    return stringResource(R.string.expense_former_member, state.formerMemberNumbers[accountId] ?: 0)
}

/**
 * Who an entry moved money between, said the way its type reads it (T-245): an expense was paid by
 * someone for others, an income was received by someone and credited to others, and a transfer is
 * simply one person to another.
 */
@Composable
internal fun subLine(expense: Expense, state: ExpenseListUiState): String {
    // .map before joining: participantLabel is itself a composable, and only an inline lambda is
    // a composable context.
    val by = expense.paidBy.keys.map { participantLabel(it, state) }.joinToString(", ")
    return when (ExpenseMath.entryType(expense)) {
        ExpenseType.TRANSFER -> stringResource(
            R.string.expense_row_transfer,
            by,
            expense.paidFor.keys.map { participantLabel(it, state) }.joinToString(", "),
        )
        ExpenseType.INCOME ->
            stringResource(R.string.expense_row_received_by, by, forWhomLabel(expense, state))
        ExpenseType.EXPENSE ->
            stringResource(R.string.expense_row_by, by, forWhomLabel(expense, state))
    }
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

/**
 * Owed is red, owing-to-you is green, square is grey — as the web's balanceColor does it
 * (T-182, T-241). Green rather than the theme's primary: the brand colour belongs to headings and
 * buttons, and a number that means something reads by the colour money is already read in.
 */
@Composable
internal fun balanceColor(cents: Long) = when {
    cents < 0 -> MaterialTheme.colorScheme.error
    cents > 0 -> LocalPositiveBalanceColor.current
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
                AppFormat.day(state.closedAt ?: 0L, appLocale()),
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
            state.voteError?.let {
                Text(
                    it.asString(),
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
