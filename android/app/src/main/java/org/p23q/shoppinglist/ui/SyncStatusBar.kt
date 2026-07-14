package org.p23q.shoppinglist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // The list screen already surfaces the recency line and doesn't need the loud attention banner
    // (Overview owns that), so it opts out (T-36).
    showAttention: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showAttention && state.blockedCount > 0) {
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

/**
 * A compact status dot for screens that don't have room for the full recency line (T-63's ask to
 * fold the list screen's "Synced 5 min ago" line into its top controls row instead of giving it a
 * whole line): grey = never synced, primary = synced, error = failed, a small spinner while a sync
 * is in flight. The pending count rides alongside when there is one; the full [syncRecencyText]
 * rides along as the content description so TalkBack users still get the whole sentence.
 */
@Composable
fun SyncStatusMarker(state: SyncState, nowMs: Long, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = syncRecencyText(state, nowMs) },
    ) {
        if (state.inProgress) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            val dotColor = when {
                state.lastError != null -> MaterialTheme.colorScheme.error
                state.lastSyncAt == null -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        }
        if (state.pendingCount > 0) {
            Spacer(Modifier.width(4.dp))
            Text(text = "${state.pendingCount}", style = MaterialTheme.typography.labelSmall)
        }
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
