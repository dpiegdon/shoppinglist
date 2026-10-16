package org.p23q.shoppinglist.ui

import org.jetbrains.compose.resources.StringResource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.core.sync.SyncState
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.ago_days
import org.p23q.shoppinglist.resources.ago_hours
import org.p23q.shoppinglist.resources.ago_just_now
import org.p23q.shoppinglist.resources.ago_minutes
import org.p23q.shoppinglist.resources.sync_attention
import org.p23q.shoppinglist.resources.sync_failed_since
import org.p23q.shoppinglist.resources.sync_not_synced
import org.p23q.shoppinglist.resources.sync_pending_suffix
import org.p23q.shoppinglist.resources.sync_synced
import org.p23q.shoppinglist.resources.sync_syncing

/**
 * Pure formatting logic for the sync-health surface (T-47) — no Compose/Robolectric needed.
 *
 * Since T-111 these helpers return a [UiText] rather than a String, so what is asserted here is
 * which message is chosen and with which arguments; the wording itself now lives in strings.xml,
 * where a translator can change it without breaking a test. Returning UiText is what let this stay
 * a plain JUnit test: resolving to a String needs a Context, and a Context needs Robolectric.
 */
class SyncStatusBarTest {

    private val now = 1_000_000_000L

    private fun res(resource: StringResource, vararg args: Any) = UiText.Res(resource, args.toList())

    @Test
    fun `in progress reads Syncing`() {
        assertEquals(res(Res.string.sync_syncing), syncRecencyText(SyncState(inProgress = true), now))
    }

    @Test
    fun `never synced reads Not synced yet`() {
        assertEquals(res(Res.string.sync_not_synced), syncRecencyText(SyncState(lastSyncAt = null), now))
    }

    @Test
    fun `relative times bucket into just now, minutes, hours, days`() {
        assertEquals(
            res(Res.string.sync_synced, res(Res.string.ago_just_now)),
            syncRecencyText(SyncState(lastSyncAt = now - 30_000L), now),
        )
        assertEquals(
            res(Res.string.sync_synced, res(Res.string.ago_minutes, 5)),
            syncRecencyText(SyncState(lastSyncAt = now - 5 * 60_000L), now),
        )
        assertEquals(
            res(Res.string.sync_synced, res(Res.string.ago_hours, 2)),
            syncRecencyText(SyncState(lastSyncAt = now - 2 * 3_600_000L), now),
        )
        assertEquals(
            res(Res.string.sync_synced, res(Res.string.ago_days, 3)),
            syncRecencyText(SyncState(lastSyncAt = now - 3 * 86_400_000L), now),
        )
    }

    @Test
    fun `pending count is appended`() {
        assertEquals(
            res(Res.string.sync_pending_suffix, res(Res.string.sync_synced, res(Res.string.ago_just_now)), 2),
            syncRecencyText(SyncState(lastSyncAt = now, pendingCount = 2), now),
        )
    }

    @Test
    fun `a recent failure shows the last good time`() {
        assertEquals(
            res(Res.string.sync_failed_since, res(Res.string.ago_minutes, 5)),
            syncRecencyText(SyncState(lastSyncAt = now - 5 * 60_000L, lastError = "boom"), now),
        )
    }

    @Test
    fun `attention text reads the same at one as at many (T-123)`() {
        // One message, the count as an argument — so no language needs a plural rule here.
        assertEquals(res(Res.string.sync_attention, 1), attentionText(1))
        assertEquals(res(Res.string.sync_attention, 3), attentionText(3))
    }
}
