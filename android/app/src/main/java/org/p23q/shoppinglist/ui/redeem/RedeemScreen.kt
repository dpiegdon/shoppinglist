package org.p23q.shoppinglist.ui.redeem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.ui.asString

/**
 * App Link entry point: a tapped `https://<server>/invite/<token>` link lands here and redeems
 * automatically, prefilled from the link's token (Notes), into the account on that server (T-292).
 */
@Composable
fun RedeemScreen(
    token: String,
    onRedeemed: (listId: String) -> Unit,
    onCancel: () -> Unit,
    /** The login route to open: no account can take the invite until one signs in (T-28, T-292). */
    onNeedsLogin: (loginRoute: String) -> Unit = {},
    /** The invite link itself, which names its server; null for a bare token. */
    link: String? = null,
    /** The account to redeem into, when that is already settled (after signing in for it). */
    accountId: String? = null,
    viewModel: RedeemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(token, link, accountId) {
        viewModel.onTokenChange(token)
        viewModel.redeem(link, accountId)
    }
    LaunchedEffect(state.redeemedListId) {
        state.redeemedListId?.let { listId ->
            onRedeemed(listId)
            viewModel.redeemedListOpened()
        }
    }
    // The VM has stashed the token; go sign in, then resume the redeem (T-28).
    LaunchedEffect(state.needsLogin) {
        state.needsLogin?.let { route ->
            onNeedsLogin(route)
            viewModel.loginOpened()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val error = state.errorMessage
        when {
            error != null -> {
                Text(error.asString(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
            }
            state.choices.isNotEmpty() -> {
                Text(stringResource(R.string.redeem_choose_account), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                state.choices.forEach { account ->
                    OutlinedButton(
                        onClick = { viewModel.chooseAccount(account.id) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("redeem-account-" + account.id),
                    ) {
                        InviteAccountLines(account)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
            }
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.redeem_joining))
                // Which account it joins with, when the phone holds several (T-292).
                state.account?.takeIf { state.several }?.let { account ->
                    Spacer(Modifier.height(8.dp))
                    InviteAccountLines(account)
                }
            }
        }
    }
}

/** An account as the invite screens name it: email, then its server, muted. */
@Composable
internal fun InviteAccountLines(account: AccountEntity) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(account.email ?: account.label, textAlign = TextAlign.Center)
        account.serverUrl?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
