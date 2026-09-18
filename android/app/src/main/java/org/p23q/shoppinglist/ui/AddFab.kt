package org.p23q.shoppinglist.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The add button on every list kind (T-168): bottom right, the way most apps do it now, and in
 * the primary colours of the full-width Add button it replaced on shopping and checklist lists.
 * Material's default FAB colour is the paler primary container, which is what made the expense
 * list look like it belonged to a different app.
 */
@Composable
fun AddFab(onClick: () -> Unit, contentDescription: String) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(Icons.Default.Add, contentDescription = contentDescription)
    }
}
