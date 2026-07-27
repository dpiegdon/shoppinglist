package org.p23q.shoppinglist.ui.settings

import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

/**
 * Human-readable staleness for a session's `last_seen_at` (T-104).
 *
 * Coarse on purpose: the server throttles `last_seen_at` writes to a 15-minute staleness window
 * (auth.LAST_SEEN_REFRESH_MS), so finer granularity would show precision the timestamp doesn't
 * have. What this needs to convey is "is this session about to idle out?", against windows
 * measured in days.
 *
 * A future [lastSeenAt] (client/server clock skew) falls into the "Active now" branch rather than
 * rendering as a negative age. Plain function rather than DateUtils.getRelativeTimeSpanString so
 * it's testable without the Android framework, and so the wording matches the web client's.
 *
 * Units are abbreviated (T-123) so no string ever has to agree with a number: "1 min ago" and
 * "20 min ago" use the same word, in every language. This deliberately matches
 * [formatBackgroundSync], which already reads this way and renders on the same Settings screen.
 * The abbreviations are ordinary translatable strings — German would use Min/Std/T — and unlike a
 * spelled-out unit they never inflect, which is what keeps plural handling out of the codebase
 * entirely.
 */
fun formatLastSeen(lastSeenAt: Long, now: Long = System.currentTimeMillis()): UiText {
    val elapsed = now - lastSeenAt
    return when {
        elapsed < 2 * MINUTE_MS -> UiText.res(R.string.last_seen_active_now)
        elapsed < HOUR_MS -> UiText.res(R.string.ago_minutes, (elapsed / MINUTE_MS).toInt())
        elapsed < DAY_MS -> UiText.res(R.string.ago_hours, (elapsed / HOUR_MS).toInt())
        else -> UiText.res(R.string.ago_days, (elapsed / DAY_MS).toInt())
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
