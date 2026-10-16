package org.p23q.shoppinglist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.core.db.AccountEntity

/**
 * One account to choose, wherever the app asks which account (A7): the new list's account, the
 * admin console's, and the invite's in its dialog and on its screen. Two lines, the account then
 * its server muted, in the plain text colour (a dialog's own content colour is the muted one, which
 * made the choices read as hints), across the full width with its ripple, and at least 48dp tall.
 *
 * With [selected] it is one of a radio group, a radio button leading; without, a tap chooses at once.
 */
@Composable
fun AccountChoiceRow(
    account: AccountEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
) {
    val clickable = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    val padding = if (selected != null) PaddingValues(vertical = 4.dp) else PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).then(clickable).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp))
        Column {
            Text(accountName(account), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            account.serverUrl?.let { url ->
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
