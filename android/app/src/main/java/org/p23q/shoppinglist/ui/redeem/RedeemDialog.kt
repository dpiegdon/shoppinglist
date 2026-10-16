package org.p23q.shoppinglist.ui.redeem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.action_cancel
import org.p23q.shoppinglist.resources.action_join
import org.p23q.shoppinglist.resources.redeem_choose_account
import org.p23q.shoppinglist.resources.redeem_code
import org.p23q.shoppinglist.resources.redeem_title
import org.p23q.shoppinglist.ui.AccountChoiceRow
import org.p23q.shoppinglist.ui.FieldLabel
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.stringResource
import org.p23q.shoppinglist.ui.theme.TuppuTextButton

/** Notes: stringResource(Res.string.redeem_title) — the paste-a-code fallback for invite links, reachable from the drawer. */
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
        title = { Text(stringResource(Res.string.redeem_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text(stringResource(Res.string.redeem_code)) },
                    singleLine = true,
                )
                state.errorMessage?.let { error ->
                    Text(text = error.asString(), color = MaterialTheme.colorScheme.error)
                }
                // Several accounts could take it (T-292): the user picks which joins.
                if (state.choices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FieldLabel(stringResource(Res.string.redeem_choose_account))
                    state.choices.forEach { account ->
                        AccountChoiceRow(
                            account = account,
                            onClick = { viewModel.chooseAccount(account.id) },
                            enabled = !state.isLoading,
                            modifier = Modifier.testTag("redeem-account-" + account.id),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TuppuTextButton(onClick = { viewModel.redeem() }, enabled = !state.isLoading) { Text(stringResource(Res.string.action_join)) }
        },
        dismissButton = { TuppuTextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
