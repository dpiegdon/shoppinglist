package org.p23q.shoppinglist.ui.redeem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.asString

/** Notes: stringResource(R.string.redeem_title) — the paste-a-code fallback for invite links, reachable from the drawer. */
@Composable
fun RedeemDialog(
    onRedeemed: (listId: String) -> Unit,
    onDismiss: () -> Unit,
    /** The login route to open: no account on this phone can take the invite yet (T-292). */
    onNeedsLogin: (loginRoute: String) -> Unit = {},
    viewModel: RedeemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.redeemedListId) {
        state.redeemedListId?.let { listId ->
            onRedeemed(listId)
            viewModel.redeemedListOpened()
        }
    }
    LaunchedEffect(state.needsLogin) {
        state.needsLogin?.let { route ->
            onNeedsLogin(route)
            viewModel.loginOpened()
        }
    }

    LocalizedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redeem_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text(stringResource(R.string.redeem_code)) },
                    singleLine = true,
                )
                state.errorMessage?.let { error ->
                    Text(text = error.asString(), color = MaterialTheme.colorScheme.error)
                }
                // Several accounts could take it (T-292): the user picks which joins.
                if (state.choices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.redeem_choose_account), style = MaterialTheme.typography.labelMedium)
                    state.choices.forEach { account ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isLoading) { viewModel.chooseAccount(account.id) }
                                .padding(vertical = 8.dp)
                                .testTag("redeem-account-" + account.id),
                        ) {
                            Text(account.email ?: account.label)
                            account.serverUrl?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.redeem() }, enabled = !state.isLoading) { Text(stringResource(R.string.action_join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
