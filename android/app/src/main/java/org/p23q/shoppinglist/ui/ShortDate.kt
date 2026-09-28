package org.p23q.shoppinglist.ui

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * A calendar date (YYYY-MM-DD) in its shortest form, as the language writes it (T-323): "Oct 3" /
 * "3. Okt.", and with the year only when it is not [today]'s: "Jan 5, 2027". The web's
 * `shortDate` in lib/format.ts, tested with the same example dates.
 *
 * Here rather than in :core's AppFormat: a month-and-day form is a CLDR skeleton, which only ICU
 * resolves per language (java.time's localized styles all carry the year, and its skeleton
 * lookup is newer than Android's). Formatted at UTC midnight, so no zone moves the day.
 */
fun shortDate(isoDate: String, locale: Locale, today: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    val skeleton = if (today.take(4) == isoDate.take(4)) "MMMd" else "yMMMd"
    val format = DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = TimeZone.GMT_ZONE }
    return format.format(Date(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
}
