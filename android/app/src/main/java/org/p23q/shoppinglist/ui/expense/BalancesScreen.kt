package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.ExpenseMath

/**
 * Who is up and who is down on an expenses list (T-154).
 *
 * Everyone named anywhere appears, including people who have since left: their debts and credits
 * do not leave with them. The rows always sum to zero.
 */
@Composable
fun BalancesScreen(viewModel: ExpenseListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(
                R.string.expense_total_spent_value,
                ExpenseMath.fromCents(state.totalCents),
                state.currency,
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
                            text = member?.email ?: participantLabel(balance.accountId, state),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.expense_paid_and_share,
                                ExpenseMath.fromCents(balance.paidCents),
                                ExpenseMath.fromCents(balance.shareCents),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "${ExpenseMath.fromCents(balance.balanceCents)} ${state.currency}",
                        style = MaterialTheme.typography.titleSmall,
                        color = balanceColor(balance.balanceCents),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
