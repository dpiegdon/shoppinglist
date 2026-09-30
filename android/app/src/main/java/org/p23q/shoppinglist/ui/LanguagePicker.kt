package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppLocale

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(
    selected: AppLocale,
    onSelect: (AppLocale) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Looks like the picker it is (C5): an outlined field labelled "Language" with a drop-down
    // arrow, the label joined to the value, as the web's <select>.
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.settings_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("language-picker"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The entries are endonyms and so need no translating, but a popup is still its own
            // window (T-131) — this is what carries the layout direction in, so the list reads
            // right-to-left when the app is set to Arabic.
            LocalizedOverlay {
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
}
