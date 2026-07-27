package org.p23q.shoppinglist.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.data.sync.SyncState

/** Pure formatting logic for the sync-health surface (T-47) — no Compose/Robolectric needed. */
class SyncStatusBarTest {

    private val now = 1_000_000_000L

    @Test
    fun `in progress reads Syncing`() {
        assertEquals("Syncing…", syncRecencyText(SyncState(inProgress = true), now))
    }

    @Test
    fun `never synced reads Not synced yet`() {
        assertEquals("Not synced yet", syncRecencyText(SyncState(lastSyncAt = null), now))
    }

    @Test
    fun `relative times bucket into just now, minutes, hours, days`() {
        assertEquals("Synced just now", syncRecencyText(SyncState(lastSyncAt = now - 30_000L), now))
        assertEquals("Synced 5 min ago", syncRecencyText(SyncState(lastSyncAt = now - 5 * 60_000L), now))
        assertEquals("Synced 2 h ago", syncRecencyText(SyncState(lastSyncAt = now - 2 * 3_600_000L), now))
        assertEquals("Synced 3 d ago", syncRecencyText(SyncState(lastSyncAt = now - 3 * 86_400_000L), now))
    }

    @Test
    fun `pending count is appended`() {
        assertEquals(
            "Synced just now · 2 pending",
            syncRecencyText(SyncState(lastSyncAt = now, pendingCount = 2), now),
        )
    }

    @Test
    fun `a recent failure shows the last good time`() {
        assertEquals(
            "Sync failed · last ok 5 min ago",
            syncRecencyText(SyncState(lastSyncAt = now - 5 * 60_000L, lastError = "boom"), now),
        )
    }

    @Test
    fun `attention text is singular for one and plural otherwise`() {
        // Label-then-count (T-123): identical wording at 1 and at 3, so no plural rule is needed.
        assertEquals("Items needing attention: 1", attentionText(1))
        assertEquals("Items needing attention: 3", attentionText(3))
    }
}
