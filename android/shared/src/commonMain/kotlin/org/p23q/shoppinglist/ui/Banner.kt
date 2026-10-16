package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The padding inside every banner (A9): the attention banner, an account's signed-out or outdated
 * banner, the blocked-row banner and the server's message. With the rounded [MaterialTheme.shapes]
 * `small` corners and an inset from the screen's edges, it is one shape wherever a banner shows.
 */
val BannerPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * A red banner (A9): the error container's colours, rounded, padded by [BannerPadding], across the
 * width it is given; the caller insets it. With [onClick] the whole banner is the target.
 */
@Composable
fun ErrorBanner(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val inner: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().padding(BannerPadding)) { content() } }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = modifier.fillMaxWidth(),
            content = inner,
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = modifier.fillMaxWidth(),
            content = inner,
        )
    }
}
