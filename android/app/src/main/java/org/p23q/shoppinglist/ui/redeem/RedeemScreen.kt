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

/** App Link entry point: a tapped `https://<server>/invite/<token>` link lands here and redeems
 * automatically, prefilled from the link's token (Notes).
 */
@Composable
fun RedeemScreen(
    token: String,
    onRedeemed: (listId: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: RedeemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(token) {
        viewModel.onTokenChange(token)
        viewModel.redeem()
    }
    LaunchedEffect(state.redeemedListId) { state.redeemedListId?.let(onRedeemed) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val error = state.errorMessage
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCancel) { Text("Back") }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Joining list…")
        }
    }
}
