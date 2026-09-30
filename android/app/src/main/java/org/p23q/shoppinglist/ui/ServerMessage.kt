package org.p23q.shoppinglist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 *
 * Drawn as the web's info banner (T-339): a thin border, a muted stripe along the start edge and
 * the text in the plain text colour, so it reads as a notice rather than as one more list card.
 */
@Composable
fun ServerMessage(text: String, tag: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .testTag("$tag-stripe")
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(tag).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
