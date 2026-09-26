package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The server's one-line message (T-315), as the login page and the overview show it: a slim,
 * neutral "info" line, apart from the error-coloured banners. Not dismissable, and plain text: a
 * String, never an AnnotatedString, so nothing in it (a link included) is ever made tappable.
 */
@Composable
fun ServerMessage(text: String, tag: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(tag).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
