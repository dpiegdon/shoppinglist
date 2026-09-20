package org.p23q.shoppinglist.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.update.AvailableUpdate

/**
 * The blocking notice shown while this app is too old for its server (T-240).
 *
 * It replaces the whole UI rather than sitting over it: every request now comes back 426, so
 * anything behind it could only show stale data and a sync that never succeeds. Updating is not
 * optional, so the check starts by itself the moment this appears — [onOpened] — and what it finds
 * is offered exactly the way the ordinary update prompt offers it. When the server has no newer
 * app (or cannot be asked), the notice says so and offers Retry, because only whoever runs the
 * server can fix that.
 *
 * Hoisted state throughout rather than a hiltViewModel() default, so it can be rendered straight
 * from a unit test — see LoginScreen (T-127); [org.p23q.shoppinglist.ui.ShoppingListNavHost] hands
 * it the one UpdateViewModel the whole app shares.
 */
@Composable
fun UpdateRequiredScreen(
    status: UpdateStatus = UpdateStatus.Idle,
    update: AvailableUpdate? = null,
    /** Fires the check the moment this screen appears — it is not optional, so nothing waits. */
    onOpened: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDownload: (AvailableUpdate) -> Unit = {},
    currentVersion: String = BuildConfig.VERSION_NAME,
) {
    LaunchedEffect(Unit) { onOpened() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.update_required_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.update_required_body),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        // Idle is only ever the moment before the check this screen starts has reported; every way
        // of not having an update to offer — including a check that could not be made — ends up in
        // the branch that names the server's operator, so there is no dead end to get stuck in.
        when (status) {
            UpdateStatus.Idle, UpdateStatus.Checking -> {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp).size(32.dp))
                Text(
                    text = stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            is UpdateStatus.Available -> {
                Text(
                    text = stringResource(R.string.update_available_body, status.version, currentVersion),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
                Button(
                    // Only with an update actually in hand: the button exists to open its URL.
                    onClick = { update?.let(onDownload) },
                    enabled = update != null,
                    modifier = Modifier.padding(top = 16.dp).testTag("update-required-download"),
                ) {
                    Text(stringResource(R.string.action_update))
                }
            }
            is UpdateStatus.UpToDate, UpdateStatus.Failed -> {
                Text(
                    text = stringResource(R.string.update_required_unavailable),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 16.dp).testTag("update-required-retry"),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}
