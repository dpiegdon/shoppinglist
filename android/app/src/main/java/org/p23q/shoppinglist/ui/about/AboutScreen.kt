package org.p23q.shoppinglist.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.update.UpdateStatus

/**
 * What the app is and where its name comes from (T-224).
 *
 * Hoisted state throughout rather than a hiltViewModel() default, so the screen can be rendered
 * straight from a unit test — see LoginScreen (T-127). [Nav] hands it the one UpdateViewModel the
 * whole NavHost shares.
 *
 * The web's About page carries everything here except the "App updates" block: a browser fetches
 * the current bundle from the server on every load, so it has nothing to offer to update. The
 * block is Android-only by design, not by oversight.
 */
@Composable
fun AboutScreen(
    /** What the check made on opening this screen found (T-149); it used to be opening settings. */
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    autoCheckEnabled: Boolean = true,
    onSetAutoCheckEnabled: (Boolean) -> Unit = {},
    /** Fires the check the moment this screen appears (T-149). */
    onOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LaunchedEffect(Unit) { onOpened() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // The mark over the name, exactly as the login screen opens (T-213/T-216), at 72dp.
            Image(
                painter = painterResource(R.drawable.ic_brand_logo),
                contentDescription = null,
                // Tagged because the mark is decorative: no contentDescription means no semantics
                // of its own, and the test has to be able to say it is on the page.
                modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally).testTag("about-mark"),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.about_tagline),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_version, appVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))

            // App updates (T-135), moved here from settings unchanged (T-224): keeping the app
            // current is about the app, not about the account. Device-local like the notification
            // toggle — whether this phone checks is a property of the phone, so it isn't synced.
            // Off means no request at all, not a silent check.
            Text(stringResource(R.string.about_updates), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_auto_update_check))
                    Text(
                        stringResource(R.string.about_auto_update_check_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoCheckEnabled, onCheckedChange = onSetAutoCheckEnabled)
            }
            // The answer to the check this screen made when it opened (T-149). Silent while
            // switched off, which is also when no request was made.
            val updateLine = when (updateStatus) {
                UpdateStatus.Idle -> null
                UpdateStatus.Checking -> stringResource(R.string.update_checking)
                is UpdateStatus.UpToDate -> stringResource(R.string.update_up_to_date, updateStatus.version)
                is UpdateStatus.Available -> stringResource(R.string.update_available_status, updateStatus.version)
                UpdateStatus.Failed -> stringResource(R.string.update_check_failed)
            }
            if (updateLine != null) {
                Text(
                    updateLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateStatus is UpdateStatus.Available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // Small, at the bottom, as on the web page.
        Text(
            text = stringResource(R.string.about_license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}
