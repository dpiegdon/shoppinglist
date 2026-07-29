package org.p23q.shoppinglist.ui.redeem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.asString

/** Notes: stringResource(R.string.redeem_title) — the paste-a-code fallback for invite links, reachable from the drawer. */
@Composable
fun RedeemDialog(
    onRedeemed: (listId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RedeemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.redeemedListId) { state.redeemedListId?.let(onRedeemed) }

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
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.redeem() }, enabled = !state.isLoading) { Text(stringResource(R.string.action_join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
