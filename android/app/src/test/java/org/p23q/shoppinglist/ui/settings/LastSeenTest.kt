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
        assertEquals("20 min ago", formatLastSeen(now - 20 * minute, now))
        assertEquals("5 h ago", formatLastSeen(now - 5 * hour, now))
        assertEquals("1 d ago", formatLastSeen(now - 25 * hour, now))
        assertEquals("3 d ago", formatLastSeen(now - 3 * day, now))
    }

    @Test
    fun `a count of one reads exactly like any other count (T-123)`() {
        // The whole point of abbreviated units: no branch on the number, so no language ever
        // needs a plural rule here. Same word at 1 as at 20.
        assertEquals("1 h ago", formatLastSeen(now - hour - minute, now))
        assertEquals("1 d ago", formatLastSeen(now - day - hour, now))
        assertEquals("2 min ago", formatLastSeen(now - 2 * minute, now))
    }

    @Test
    fun `clock skew does not render as a future time`() {
        assertEquals("Active now", formatLastSeen(now + 5 * hour, now))
    }

    @Test
    fun `an android session nearing its 62-day window still reads in days`() {
        assertEquals("61 d ago", formatLastSeen(now - 61 * day, now))
    }
}
