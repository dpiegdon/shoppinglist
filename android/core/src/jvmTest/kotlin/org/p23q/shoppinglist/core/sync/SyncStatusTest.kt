package org.p23q.shoppinglist.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStatusTest {

    private val status = SyncStatus()

    @Test
    fun `an account that has never synced holds the aggregate at none`() {
        status.account("a").succeeded(at = 100, pending = 0, blocked = 0)
        status.account("b").seed(pending = 2, blocked = 0)

        assertNull(status.state.value.lastSyncAt)
    }

    @Test
    fun `a signed-out or outdated account is left out of the last sync and error, not of the counts (T-304)`() {
        status.account("a").succeeded(at = 100, pending = 1, blocked = 0)
        status.account("b").failed("offline", pending = 2, blocked = 1)
        status.account("b").stoppedUnauthorized(pending = 2, blocked = 1)
        status.account("c").seed(pending = 3, blocked = 0)
        status.setActive("c", false)

        val state = status.state.value
        assertEquals(100L, state.lastSyncAt)
        assertNull(state.lastError)
        assertEquals(6, state.pendingCount)
        assertEquals(1, state.blockedCount)
    }

    @Test
    fun `an outdated account is left out until it syncs again (T-304)`() {
        status.account("a").succeeded(at = 100, pending = 0, blocked = 0)
        status.account("b").stoppedOutdated(pending = 0, blocked = 0)
        assertEquals(100L, status.state.value.lastSyncAt)

        status.account("b").succeeded(at = 50, pending = 0, blocked = 0)

        assertEquals("counted again", 50L, status.state.value.lastSyncAt)
    }

    @Test
    fun `with no account able to sync every account is taken (T-304)`() {
        status.account("a").failed("offline", pending = 0, blocked = 0)
        status.setActive("a", false)

        assertEquals("offline", status.state.value.lastError)
    }
}
