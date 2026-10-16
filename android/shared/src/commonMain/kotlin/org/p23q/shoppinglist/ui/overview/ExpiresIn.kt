package org.p23q.shoppinglist.ui.overview

import org.p23q.shoppinglist.core.Clock
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.overview_invite_expires_days
import org.p23q.shoppinglist.resources.overview_invite_expires_hours
import org.p23q.shoppinglist.resources.overview_invite_expires_minutes
import org.p23q.shoppinglist.ui.UiText

/**
 * How long an invite still stands, for its card on the overview (T-233). The same coarse,
 * abbreviated units as [org.p23q.shoppinglist.ui.settings.formatLastSeen], for the same reasons:
 * an invite lives seven days, so "Expires in 5 d" is the precision worth stating, and no string
 * has to agree with its number. Never below one minute — the server only returns invites that are
 * still live, and "in 0 min" would read as already gone. Matches the web client's formatExpiresIn.
 */
fun formatExpiresIn(expiresAt: Long, now: Long = Clock.System.nowMillis()): UiText {
    val remaining = maxOf(expiresAt - now, MINUTE_MS)
    return when {
        remaining < HOUR_MS -> UiText.res(Res.string.overview_invite_expires_minutes, (remaining / MINUTE_MS).toInt())
        remaining < DAY_MS -> UiText.res(Res.string.overview_invite_expires_hours, (remaining / HOUR_MS).toInt())
        else -> UiText.res(Res.string.overview_invite_expires_days, (remaining / DAY_MS).toInt())
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
