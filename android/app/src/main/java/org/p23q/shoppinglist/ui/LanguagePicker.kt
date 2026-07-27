package org.p23q.shoppinglist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppLocale

/**
 * The language chooser (T-127), used on the login screen and in Settings.
 *
 * It appears on login because the choice has to be available BEFORE an account exists — which is
 * also why the preference is device-local rather than account-synced (see LocalePreferenceStore):
 * syncing would add a second source of truth and a conflict rule for exactly the case where nobody
 * is signed in yet.
 *
 * Each language is listed in its OWN language, never translated into the current UI language. That
 * is the standard convention, and the only way a user who has landed in a script they cannot read
 * can find their way back out — so the entries deliberately do not go through a string resource.
 *
 * Stateless: [selected] and [onSelect] are hoisted so this can be previewed and tested without a
 * DataStore, and so both hosts drive it from their own ViewModel.
 */
@Composable
fun LanguagePicker(
    selected: AppLocale,
    onSelect: (AppLocale) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = selected.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLocale.entries.forEach { locale ->
                DropdownMenuItem(
                    text = { Text(locale.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(locale)
                    },
                )
            }
        }
    }
}
