package org.p23q.shoppinglist.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-104: staleness wording for the Sessions list in Settings. */
class LastSeenTest {

    private val now = 1_760_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `background-sync diagnostic reads never, just now, then coarsens (T-112)`() {
        assertEquals("never", formatBackgroundSync(0L, now))
        assertEquals("just now", formatBackgroundSync(now - 30_000, now))
        assertEquals("20 min ago", formatBackgroundSync(now - 20 * minute, now))
        assertEquals("3 h ago", formatBackgroundSync(now - 3 * hour, now))
        assertEquals("2 d ago", formatBackgroundSync(now - 2 * day, now))
    }

    @Test
    fun `very recent activity reads as active`() {
        assertEquals("Active now", formatLastSeen(now, now))
        assertEquals("Active now", formatLastSeen(now - 90_000, now))
    }

    @Test
    fun `falls back to minutes, hours, then days as it ages`() {
        assertEquals("20 minutes ago", formatLastSeen(now - 20 * minute, now))
        assertEquals("5 hours ago", formatLastSeen(now - 5 * hour, now))
        assertEquals("yesterday", formatLastSeen(now - 25 * hour, now))
        assertEquals("3 days ago", formatLastSeen(now - 3 * day, now))
    }

    @Test
    fun `singular units are not pluralized`() {
        // Hours is the only reachable singular: "1 minute ago" is swallowed by the 2-minute
        // "Active now" floor, and "1 day ago" by the "yesterday" branch.
        assertEquals("1 hour ago", formatLastSeen(now - hour - minute, now))
    }

    @Test
    fun `clock skew does not render as a future time`() {
        assertEquals("Active now", formatLastSeen(now + 5 * hour, now))
    }

    @Test
    fun `an android session nearing its 62-day window still reads in days`() {
        assertEquals("61 days ago", formatLastSeen(now - 61 * day, now))
    }
}
