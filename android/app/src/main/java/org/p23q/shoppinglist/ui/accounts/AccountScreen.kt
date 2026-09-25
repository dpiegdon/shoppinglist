package org.p23q.shoppinglist.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.accountName
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.settings.formatLastSeen

/**
 * One account (T-292): what used to be the account half of Settings, for this account alone, and
 * the two ways to part with it — delete it on its server, or only remove it from this phone.
 */
@Composable
fun AccountScreen(
    onGone: (AccountGone) -> Unit,
    onSignIn: () -> Unit,
    onOpenAdmin: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val account = state.account
    // A signed-out account has no session to ask with (T-300): its server sections wait for the
    // sign-in, and load once it is back.
    val signedOut = account?.let { it.isServer && !it.signedIn } == true

    LaunchedEffect(signedOut, account?.isServer) {
        // The local area has no server to ask (T-293).
        if (!signedOut && account?.isServer == true) {
            viewModel.loadSessions()
            viewModel.loadInitials()
        }
    }
    LaunchedEffect(state.gone) { state.gone?.let(onGone) }

    if (account != null && !account.isServer) {
        LocalAreaAccount(state, onRemove = { viewModel.requestRemove() })
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(account?.let { accountName(it) }.orEmpty(), style = MaterialTheme.typography.titleMedium)
        account?.serverUrl?.let {
            Text(stringResource(R.string.settings_server, it), style = MaterialTheme.typography.bodySmall)
        }
        when (account?.status()) {
            // The overview's banner, here too (T-300).
            AccountStatus.SIGNED_OUT -> Surface(
                onClick = onSignIn,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("account-sign-in"),
            ) {
                Text(
                    stringResource(R.string.overview_account_signed_out),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            AccountStatus.OUTDATED -> {
                Text(stringResource(R.string.accounts_state_outdated), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.accounts_outdated_help), style = MaterialTheme.typography.bodySmall)
            }
            else -> Unit
        }
        Spacer(Modifier.height(16.dp))

        // This server's admin console, for each admin account (T-300); the drawer offers only the
        // first one's.
        if (account?.isAdmin == true && account.status() == AccountStatus.SIGNED_IN) {
            OutlinedButton(onClick = onOpenAdmin, modifier = Modifier.fillMaxWidth().testTag("account-admin")) {
                Text(stringResource(R.string.nav_server_admin))
            }
            Spacer(Modifier.height(16.dp))
        }

        if (!signedOut) {
            Text(stringResource(R.string.settings_default_currency), style = MaterialTheme.typography.titleMedium)
            var currencyInput by remember(state.defaultCurrency) { mutableStateOf(state.defaultCurrency) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = currencyInput,
                    onValueChange = { currencyInput = it },
                    label = { Text(stringResource(R.string.settings_currency)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.updateCurrency(currencyInput) }) { Text(stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.height(16.dp))

            // Shown as a small badge on shared-list item rows so collaborators can see who last
            // touched an item (T-64); defaults to the email's initials until customized here.
            Text(stringResource(R.string.settings_display_initials), style = MaterialTheme.typography.titleMedium)
            var initialsInput by remember(state.initials) { mutableStateOf(state.initials ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = initialsInput,
                    onValueChange = { initialsInput = it },
                    label = { Text(stringResource(R.string.settings_initials)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.updateInitials(initialsInput) }) { Text(stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Developer-only escape hatch for a self-signed dev server, per account: present only in
        // debug builds, and release builds ignore the flag even if set (DevCertTrust).
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_trust_self_signed), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_trust_self_signed_help), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = account?.allowSelfSignedCerts ?: false,
                    onCheckedChange = { viewModel.setAllowSelfSignedCerts(it) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (!signedOut) {
            Text(stringResource(R.string.settings_change_password), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.currentPassword,
                onValueChange = viewModel::onCurrentPasswordChange,
                label = { Text(stringResource(R.string.settings_current_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.newPassword,
                onValueChange = viewModel::onNewPasswordChange,
                label = { Text(stringResource(R.string.settings_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::changePassword) { Text(stringResource(R.string.settings_change_password)) }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_change_email), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.newEmail,
                onValueChange = viewModel::onNewEmailChange,
                label = { Text(stringResource(R.string.settings_new_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.changeEmailPassword,
                onValueChange = viewModel::onChangeEmailPasswordChange,
                label = { Text(stringResource(R.string.settings_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::changeEmail) { Text(stringResource(R.string.settings_change_email)) }
            Spacer(Modifier.height(16.dp))
        }

        state.errorMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        state.infoMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))

        if (!signedOut) {
            Text(stringResource(R.string.settings_sessions), style = MaterialTheme.typography.titleMedium)
            state.sessions.forEach { session ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        val label = session.deviceLabel ?: stringResource(R.string.settings_unknown_device)
                        Text(if (session.current) "$label ${stringResource(R.string.settings_this_device)}" else label)
                        // The current session is active by definition — this request is it. Its stored
                        // lastSeenAt would read as up to 15 minutes stale (auth.LAST_SEEN_REFRESH_MS).
                        Text(
                            if (session.current) {
                                stringResource(R.string.last_seen_active_now)
                            } else {
                                formatLastSeen(session.lastSeenAt).asString()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!session.current) {
                        TextButton(onClick = { viewModel.revokeSession(session.id) }) { Text(stringResource(R.string.action_revoke)) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium)
        // Removing only affects this phone, so it is the quieter of the two.
        OutlinedButton(
            onClick = { viewModel.requestRemove() },
            modifier = Modifier.fillMaxWidth().testTag("account-remove"),
        ) { Text(stringResource(R.string.account_remove)) }
        // Filled red (T-112): deleting on the server is final. Confirmation still gates it. Only
        // with a session: the server would refuse it with 401, read as a wrong password (T-300).
        if (!signedOut) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::requestDeleteAccount,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("account-delete"),
            ) { Text(stringResource(R.string.account_delete_on_server)) }
        }
    }

    if (state.isRemoveConfirmOpen) {
        LocalizedAlertDialog(
            onDismissRequest = viewModel::cancelRemove,
            title = { Text(stringResource(R.string.account_remove_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.account_remove_body))
                    if (state.unpushedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.account_remove_unpushed, state.unpushedCount),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.account_remove_copy_hint))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRemove() }, modifier = Modifier.testTag("account-remove-confirm")) {
                    Text(stringResource(R.string.account_remove_confirm))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRemove) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (state.isDeleteConfirmOpen) {
        LocalizedAlertDialog(
            onDismissRequest = viewModel::cancelDeleteAccount,
            title = { Text(stringResource(R.string.settings_delete_account_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_delete_account_body))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.deleteAccountPassword,
                        onValueChange = viewModel::onDeleteAccountPasswordChange,
                        label = { Text(stringResource(R.string.settings_confirm_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDeleteAccount) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelDeleteAccount) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The local area's screen (T-293): what it is, how many lists it holds, and its removal, which
 * waits until it holds none: they would be gone for good, and there is no server to keep them.
 */
@Composable
private fun LocalAreaAccount(state: AccountUiState, onRemove: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.accounts_state_local), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.local_area_note_body), style = MaterialTheme.typography.bodyMedium)
        state.listCount?.let { count ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.account_local_list_count, count), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = onRemove,
            enabled = state.listCount == 0,
            modifier = Modifier.fillMaxWidth().testTag("account-remove"),
        ) { Text(stringResource(R.string.account_remove)) }
        if (state.listCount != null && state.listCount > 0) {
            Text(
                stringResource(R.string.account_remove_local_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
