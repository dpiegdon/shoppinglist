package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a screen says where its rows would be when it has none (A20): the overview, a list, the
 * ledger, the registry, an account's section. One style for all of them, as the web's
 * `.empty-state`: muted, at the start, in the body text size.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(padding),
    )
}
