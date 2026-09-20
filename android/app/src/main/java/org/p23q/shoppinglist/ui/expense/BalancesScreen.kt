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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppFormat
import org.p23q.shoppinglist.data.Expense
import org.p23q.shoppinglist.data.ExpenseMath
import org.p23q.shoppinglist.ui.appLocale
import java.time.LocalDate

/**
 * Who is up and who is down on an expenses list (T-154), and below it who should pay whom to make
 * it all zero (T-165). The Balances half of the expense list screen's selector (T-172), so it takes
 * that screen's state rather than a view model of its own.
 *
 * Everyone named anywhere appears, including people who have since left: their debts and credits
 * do not leave with them. The rows always sum to zero. Reimburse on a transfer hands a pre-filled
 * expense to [onReimburse]; what gets saved is an ordinary expense, so nothing here is stored.
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(
                R.string.expense_total_spent_value,
                AppFormat.money(state.totalCents, state.currency, appLocale()),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.balances, key = { it.accountId }) { balance ->
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
                            text = stringResource(
                                R.string.expense_paid_and_share,
                                AppFormat.number(balance.paidCents, appLocale()),
                                AppFormat.number(balance.shareCents, appLocale()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = AppFormat.signedMoney(balance.balanceCents, state.currency, appLocale()),
                        style = MaterialTheme.typography.titleSmall,
                        color = balanceColor(balance.balanceCents),
                    )
                }
                HorizontalDivider()
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
                itemsIndexed(state.transfers, key = { index, _ -> "transfer-$index" }) { _, transfer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.expense_transfer,
                                label(transfer.from),
                                label(transfer.to),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = AppFormat.money(transfer.cents, state.currency, appLocale()),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.canRecord(transfer)) {
                            val amount = ExpenseMath.fromCents(transfer.cents)
                            TextButton(
                                onClick = {
                                    onReimburse(
                                        ExpensePrefill(
                                            name = settlementTitle,
                                            // Equal split of one on each side: the total drives the
                                            // amounts, so editing it is how a partial settlement works.
                                            expense = Expense(
                                                paidBy = mapOf(transfer.from to amount),
                                                equalBy = true,
                                                paidFor = mapOf(transfer.to to amount),
                                                equalFor = true,
                                                date = LocalDate.now().toString(),
                                            ),
                                        ),
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.expense_reimburse))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
