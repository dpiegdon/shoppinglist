package org.p23q.shoppinglist.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.data.accountLine

/**
 * The one-time note shown when the local area is created (T-293): what its lists can and cannot
 * do. From the start screen and from Accounts alike.
 */
@Composable
fun LocalAreaNote(onDismiss: () -> Unit) {
    LocalizedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_area_note_title)) },
        text = { Text(stringResource(R.string.local_area_note_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(LOCAL_AREA_NOTE_OK_TAG)) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

internal const val LOCAL_AREA_NOTE_OK_TAG = "local-area-note-ok"

/**
 * What an account is called on screen: its email, or for the local area its name in the app's
 * current language (T-293). Never the stored label for the local area, which is empty: a
 * translated name stored once would stay in the language it was created in.
 */
@Composable
fun accountName(account: AccountEntity): String =
    if (account.isServer) account.email ?: account.label else stringResource(R.string.accounts_state_local)

/** [accountLine] for the screens, with the local area named as [accountName] names it. */
@Composable
fun accountLineText(account: AccountEntity): String =
    if (account.isServer) accountLine(account) else stringResource(R.string.accounts_state_local)
