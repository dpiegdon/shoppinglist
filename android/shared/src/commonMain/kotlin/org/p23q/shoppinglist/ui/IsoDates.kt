package org.p23q.shoppinglist.ui

/*
 * Calendar dates as the app stores and sends them: YYYY-MM-DD, no time, no zone. A date picker
 * works in milliseconds at UTC midnight, so these convert between the two in plain arithmetic,
 * the same on every platform.
 */

private const val DAY_MS = 24 * 60 * 60 * 1000L

private val isoDatePattern = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

/** Today in the device's time zone, as YYYY-MM-DD. */
expect fun todayIso(): String

/** UTC midnight of [isoDate] in epoch milliseconds, or null when it is not a real date. */
fun isoDateToUtcMillis(isoDate: String): Long? {
    val (y, m, d) = isoDatePattern.matchEntire(isoDate)?.destructured ?: return null
    val year = y.toInt()
    val month = m.toInt()
    val day = d.toInt()
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return daysFromCivil(year, month, day) * DAY_MS
}

/** The date, YYYY-MM-DD, that [millis] falls on at UTC. */
fun utcMillisToIsoDate(millis: Long): String {
    val days = millis.floorDiv(DAY_MS)
    // Howard Hinnant's civil_from_days.
    val z = days + 719468
    val era = z.floorDiv(146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

private fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeap(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days since 1970-01-01 (Howard Hinnant's days_from_civil). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}
