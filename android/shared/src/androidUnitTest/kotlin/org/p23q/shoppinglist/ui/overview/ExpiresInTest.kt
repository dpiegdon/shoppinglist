package org.p23q.shoppinglist.ui.overview

import org.jetbrains.compose.resources.StringResource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.overview_invite_expires_days
import org.p23q.shoppinglist.resources.overview_invite_expires_hours
import org.p23q.shoppinglist.resources.overview_invite_expires_minutes
import org.p23q.shoppinglist.ui.UiText

/** T-233: the countdown on an invite card. Pins the message and argument chosen, as LastSeenTest does. */
class ExpiresInTest {

    private val now = 1_760_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun res(resource: StringResource, vararg args: Any) = UiText.Res(resource, args.toList())

    @Test
    fun `counts down in the same abbreviated units, days first`() {
        assertEquals(res(Res.string.overview_invite_expires_days, 6), formatExpiresIn(now + 6 * day + 5 * hour, now))
        assertEquals(res(Res.string.overview_invite_expires_hours, 5), formatExpiresIn(now + 5 * hour + 30 * minute, now))
        assertEquals(res(Res.string.overview_invite_expires_minutes, 20), formatExpiresIn(now + 20 * minute, now))
    }

    @Test
    fun `never says an invite the server still offers has no time left`() {
        assertEquals(res(Res.string.overview_invite_expires_minutes, 1), formatExpiresIn(now + 10_000, now))
        assertEquals(res(Res.string.overview_invite_expires_minutes, 1), formatExpiresIn(now, now))
    }
}
