package org.p23q.shoppinglist.ui.redeem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.asString

/** App Link entry point: a tapped `https://<server>/invite/<token>` link lands here and redeems
 * automatically, prefilled from the link's token (Notes).
 */
@Composable
fun RedeemScreen(
    token: String,
    onRedeemed: (listId: String) -> Unit,
    onCancel: () -> Unit,
    onNeedsLogin: () -> Unit = {},
    viewModel: RedeemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(token) {
        viewModel.onTokenChange(token)
        viewModel.redeem()
    }
    LaunchedEffect(state.redeemedListId) { state.redeemedListId?.let(onRedeemed) }
    // Logged out: the token has been stashed by the VM; go log in, then resume redeem (T-28).
    LaunchedEffect(state.needsLogin) { if (state.needsLogin) onNeedsLogin() }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val error = state.errorMessage
        if (error != null) {
            Text(error.asString(), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.redeem_joining))
        }
    }
}
