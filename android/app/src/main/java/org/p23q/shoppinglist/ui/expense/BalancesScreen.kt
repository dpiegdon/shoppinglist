package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.ui.CompactButtonPadding
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.bidiIsolate
import org.p23q.shoppinglist.ui.layoutTextDirection
import org.p23q.shoppinglist.ui.theme.TuppuButton

/**
 * Who is up and who is down on a ledger (T-154), and below it who should pay whom to make it all
 * zero (T-165). The Balances half of the expense list screen's selector (T-172), so it takes that
 * screen's state rather than a view model of its own.
 *
 * Everyone named anywhere appears, including people who have since left: their debts and credits
 * do not leave with them. The rows always sum to zero. Reimburse on a transfer hands a pre-filled
 * Transfer entry to [onReimburse] (T-245); what gets saved is an ordinary entry, so nothing here
 * is stored.
 */
@Composable
fun BalancesContent(
    state: ExpenseListUiState,
    onReimburse: (ExpensePrefill) -> Unit,
) {
    val settlementTitle = stringResource(R.string.expense_settlement)

    @Composable
    fun label(accountId: String): String =
        state.members.firstOrNull { it.accountId == accountId }?.email ?: participantLabel(accountId, state)

    // Dividers only between rows, in the colour the entries tab and the lists use (T-342).
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Column(modifier = Modifier.fillMaxSize()) {
        // The same card as the entries tab (T-342). Where income exists, the net alone hides half
        // the story: it says what went out and what came in (T-245).
        LedgerSummaryCard(
            netCents = state.totalCents,
            currency = state.currency,
            breakdown = state.spent,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            itemsIndexed(state.balances, key = { _, balance -> balance.accountId }) { index, balance ->
                val member = state.members.firstOrNull { it.accountId == balance.accountId }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = member?.initials ?: "—",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.width(44.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label(balance.accountId),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            // Settled is what transfers moved, sent minus received — signed,
                            // because which way it went is the whole of what it says, and left
                            // uncoloured: only the balance itself is a position (T-245). Absent
                            // when nothing was settled, which is every ledger not yet paid back.
                            text = if (balance.settledCents == 0L) {
                                stringResource(
                                    R.string.expense_paid_and_share,
                                    AppFormat.number(balance.paidCents, appLocale()),
                                    AppFormat.number(balance.shareCents, appLocale()),
                                )
                            } else {
                                stringResource(
                                    R.string.expense_paid_share_settled,
                                    AppFormat.number(balance.paidCents, appLocale()),
                                    AppFormat.number(balance.shareCents, appLocale()),
                                    AppFormat.signedNumber(balance.settledCents, appLocale()),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = AppFormat.signedMoney(balance.balanceCents, state.currency, appLocale()),
                        style = MaterialTheme.typography.titleSmall,
                        color = balanceColor(balance.balanceCents),
                    )
                }
                if (index < state.balances.lastIndex) HorizontalDivider(color = dividerColor)
            }

            if (state.showSettleUp) {
                item(key = "settle-up") {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.expense_settle_up),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (state.transfers.isEmpty()) {
                        Text(
                            stringResource(R.string.expense_all_settled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Keyed by position: the same pair never appears twice, but the key must stay stable
                // as the list changes and an index is the simplest thing that does.
                itemsIndexed(state.transfers, key = { index, _ -> "transfer-$index" }) { index, transfer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The names isolated and the sentence in the app's reading direction, so it
                        // reads from payer to payee in Arabic too, whatever script the names are in.
                        Text(
                            text = stringResource(
                                R.string.expense_transfer,
                                bidiIsolate(label(transfer.from)),
                                bidiIsolate(label(transfer.to)),
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = layoutTextDirection()),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = AppFormat.money(transfer.cents, state.currency, appLocale()),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.canRecord(transfer)) {
                            val amount = ExpenseMath.fromCents(transfer.cents)
                            Spacer(Modifier.width(8.dp))
                            TuppuButton(
                                contentPadding = CompactButtonPadding,
                                onClick = {
                                    onReimburse(
                                        ExpensePrefill(
                                            name = settlementTitle,
                                            // A settlement is a transfer (T-245): the debtor hands
                                            // the creditor money, and the ledger's net spending
                                            // does not move. The total drives the amounts, so
                                            // editing it is how a partial settlement works.
                                            expense = Expense(
                                                paidBy = mapOf(transfer.from to amount),
                                                equalBy = true,
                                                paidFor = mapOf(transfer.to to amount),
                                                equalFor = true,
                                                date = LocalDate.now().toString(),
                                                type = ExpenseType.TRANSFER.wire,
                                            ),
                                        ),
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.expense_reimburse))
                            }
                        }
                    }
                    if (index < state.transfers.lastIndex) HorizontalDivider(color = dividerColor)
                }
            }
        }
    }
}

