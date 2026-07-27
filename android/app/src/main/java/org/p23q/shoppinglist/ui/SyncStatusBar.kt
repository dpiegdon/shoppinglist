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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.p23q.shoppinglist.data.sync.SyncState
import org.p23q.shoppinglist.R

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
                    Text(attentionText(state.blockedCount).asString(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(
            text = syncRecencyText(state, nowMs).asString(),
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
    // Hoisted: a semantics lambda is not a composition, so it cannot resolve a UiText itself.
    val recencyDescription = syncRecencyText(state, nowMs).asString()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = recencyDescription },
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

/**
 * A "now" that ticks roughly every [intervalMs] instead of being fixed at first composition, so a
 * recency label like [syncRecencyText]'s "just now" doesn't freeze indefinitely between sync-state
 * changes (T-54). [intervalMs] defaults to a minute — [syncRecencyText]'s smallest unit is "min
 * ago", so finer granularity wouldn't be visible. [clock] is a seam for tests (default: the real
 * wall clock) so ticking can be verified without depending on real time elapsing during a test run.
 */
@Composable
fun rememberTickingNowMs(intervalMs: Long = 60_000L, clock: () -> Long = System::currentTimeMillis): Long {
    val state = produceState(initialValue = clock()) {
        while (true) {
            delay(intervalMs)
            value = clock()
        }
    }
    return state.value
}

// Count after the label, not inside the sentence (T-123): "Items needing attention: 1" is fine
// English and needs no agreement, whereas "1 items need attention" would force a plural rule the
// codebase otherwise never needs. A bare plural reads naturally in label position.
internal fun attentionText(blockedCount: Int): UiText =
    UiText.res(R.string.sync_attention, blockedCount)

/**
 * The quiet one-liner: "Syncing…" while in progress, "Not synced yet" before the first success,
 * else "Synced <relative>" (or "Sync failed · last ok <relative>" when the most recent attempt
 * failed), with "· N pending" appended when local changes are still queued. [nowMs] is passed in so
 * this stays a pure, unit-testable function.
 */
internal fun syncRecencyText(state: SyncState, nowMs: Long): UiText {
    if (state.inProgress) return UiText.res(R.string.sync_syncing)
    val last = state.lastSyncAt ?: return UiText.res(R.string.sync_not_synced)
    val ago = (nowMs - last).coerceAtLeast(0)
    val relative = when {
        ago < 60_000L -> UiText.res(R.string.ago_just_now)
        ago < 3_600_000L -> UiText.res(R.string.ago_minutes, (ago / 60_000L).toInt())
        ago < 86_400_000L -> UiText.res(R.string.ago_hours, (ago / 3_600_000L).toInt())
        else -> UiText.res(R.string.ago_days, (ago / 86_400_000L).toInt())
    }
    // Nested UiText: the outer message takes the relative label as an ARGUMENT rather than being
    // concatenated with it, so both halves stay independently translatable and a translator can
    // put them in whatever order the language wants.
    val base = if (state.lastError != null) {
        UiText.res(R.string.sync_failed_since, relative)
    } else {
        UiText.res(R.string.sync_synced, relative)
    }
    return if (state.pendingCount > 0) {
        UiText.res(R.string.sync_pending_suffix, base, state.pendingCount)
    } else {
        base
    }
}
