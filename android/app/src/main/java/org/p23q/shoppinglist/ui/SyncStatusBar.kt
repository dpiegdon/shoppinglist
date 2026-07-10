package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.data.sync.SyncState

/**
 * The shared sync-health surface (T-47): a quiet recency line ("Synced 5 min ago · 2 pending"), and
 * — when the server has quarantined rows — a loud, tappable "N items need attention" banner that
 * opens the list holding one of them. Reused by Overview here and by the list screen's pull-to-
 * refresh indicator (T-36).
 */
@Composable
fun SyncStatusBar(
    state: SyncState,
    nowMs: Long,
    onAttentionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.blockedCount > 0) {
            Surface(
                onClick = onAttentionClick,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(attentionText(state.blockedCount), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(
            text = syncRecencyText(state, nowMs),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.lastError != null && state.blockedCount == 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

internal fun attentionText(blockedCount: Int): String =
    if (blockedCount == 1) "1 item needs attention" else "$blockedCount items need attention"

/**
 * The quiet one-liner: "Syncing…" while in progress, "Not synced yet" before the first success,
 * else "Synced <relative>" (or "Sync failed · last ok <relative>" when the most recent attempt
 * failed), with "· N pending" appended when local changes are still queued. [nowMs] is passed in so
 * this stays a pure, unit-testable function.
 */
internal fun syncRecencyText(state: SyncState, nowMs: Long): String {
    if (state.inProgress) return "Syncing…"
    val last = state.lastSyncAt ?: return "Not synced yet"
    val ago = (nowMs - last).coerceAtLeast(0)
    val relative = when {
        ago < 60_000L -> "just now"
        ago < 3_600_000L -> "${ago / 60_000L} min ago"
        ago < 86_400_000L -> "${ago / 3_600_000L} h ago"
        else -> "${ago / 86_400_000L} d ago"
    }
    val base = if (state.lastError != null) "Sync failed · last ok $relative" else "Synced $relative"
    return if (state.pendingCount > 0) "$base · ${state.pendingCount} pending" else base
}
