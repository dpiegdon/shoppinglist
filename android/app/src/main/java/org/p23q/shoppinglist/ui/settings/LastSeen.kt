package org.p23q.shoppinglist.ui.settings

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
 */
fun formatLastSeen(lastSeenAt: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = now - lastSeenAt
    return when {
        elapsed < 2 * MINUTE_MS -> "Active now"
        elapsed < HOUR_MS -> plural(elapsed / MINUTE_MS, "minute")
        elapsed < DAY_MS -> plural(elapsed / HOUR_MS, "hour")
        elapsed < 2 * DAY_MS -> "yesterday"
        else -> plural(elapsed / DAY_MS, "day")
    }
}

private fun plural(count: Long, unit: String): String =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
