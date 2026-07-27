package org.p23q.shoppinglist.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

/**
 * T-104: staleness wording for the Sessions list in Settings.
 *
 * Since T-111 these return a [UiText] rather than a String, so what is pinned here is which
 * message is chosen and with which argument; the wording lives in strings.xml, where a translator
 * can change it without breaking a test. That is also what keeps this a plain JUnit test —
 * resolving to a String needs a Context, and a Context needs Robolectric.
 */
class LastSeenTest {

    private val now = 1_760_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    @Test
    fun `background-sync diagnostic reads never, just now, then coarsens (T-112)`() {
        assertEquals(res(R.string.background_sync_never), formatBackgroundSync(0L, now))
        assertEquals(res(R.string.ago_just_now), formatBackgroundSync(now - 30_000, now))
        assertEquals(res(R.string.ago_minutes, 20), formatBackgroundSync(now - 20 * minute, now))
        assertEquals(res(R.string.ago_hours, 3), formatBackgroundSync(now - 3 * hour, now))
        assertEquals(res(R.string.ago_days, 2), formatBackgroundSync(now - 2 * day, now))
    }

    @Test
    fun `very recent activity reads as active`() {
        assertEquals(res(R.string.last_seen_active_now), formatLastSeen(now, now))
        assertEquals(res(R.string.last_seen_active_now), formatLastSeen(now - 90_000, now))
    }

    @Test
    fun `falls back to minutes, hours, then days as it ages`() {
        assertEquals(res(R.string.ago_minutes, 20), formatLastSeen(now - 20 * minute, now))
        assertEquals(res(R.string.ago_hours, 5), formatLastSeen(now - 5 * hour, now))
        assertEquals(res(R.string.ago_days, 1), formatLastSeen(now - 25 * hour, now))
        assertEquals(res(R.string.ago_days, 3), formatLastSeen(now - 3 * day, now))
    }

    @Test
    fun `a count of one uses the same message as any other count (T-123)`() {
        // The whole point of abbreviated units: one message per unit, the count as an argument, so
        // no language ever needs a plural rule here.
        assertEquals(res(R.string.ago_hours, 1), formatLastSeen(now - hour - minute, now))
        assertEquals(res(R.string.ago_days, 1), formatLastSeen(now - day - hour, now))
        assertEquals(res(R.string.ago_minutes, 2), formatLastSeen(now - 2 * minute, now))
    }

    @Test
    fun `clock skew does not render as a future time`() {
        assertEquals(res(R.string.last_seen_active_now), formatLastSeen(now + 5 * hour, now))
    }

    @Test
    fun `an android session nearing its 62-day window still reads in days`() {
        assertEquals(res(R.string.ago_days, 61), formatLastSeen(now - 61 * day, now))
    }
}
