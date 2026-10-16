package org.p23q.shoppinglist.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The label over a group of fields or a choice (A15): "Stores", the item's "Status", an expense's
 * "Type", a new list's "Type", list properties' "Type". One style for all of them, the muted
 * labelMedium, as the web's `.form-field label` draws them.
 */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
