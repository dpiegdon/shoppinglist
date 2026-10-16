package org.p23q.shoppinglist.ui

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

// ICU, not java.time: java.time's localized styles all carry the year, and its skeleton lookup is
// newer than Android's.
actual fun shortDate(isoDate: String, locale: Locale, today: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    val skeleton = if (today.take(4) == isoDate.take(4)) "MMMd" else "yMMMd"
    val format = DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = TimeZone.GMT_ZONE }
    return format.format(Date(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
}
