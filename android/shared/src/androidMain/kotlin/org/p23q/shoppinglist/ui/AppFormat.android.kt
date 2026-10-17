package org.p23q.shoppinglist.ui

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** java.text and java.time, which plain JVM tests run as the phone does. */
actual object AppFormat {

    actual fun money(cents: Long, currency: String?, locale: Locale): String {
        val label = currency?.trim().orEmpty()
        val code = if (isoCurrency.matches(label)) runCatching { Currency.getInstance(label) }.getOrNull() else null
        if (code != null) {
            // The currency's own precision (none for yen), set explicitly: java.text's setCurrency
            // does not change the fraction digits. An amount that really has cents keeps them
            // rather than being rounded away.
            val format = NumberFormat.getCurrencyInstance(locale.forFormatting()).apply { this.currency = code }
            val digits = if (cents % 100 != 0L) 2 else code.defaultFractionDigits.coerceAtLeast(0)
            format.minimumFractionDigits = digits
            format.maximumFractionDigits = digits
            return format.format(BigDecimal.valueOf(cents, 2))
        }
        val number = number(cents, locale)
        return if (label.isNotEmpty()) "$number $label" else number
    }

    actual fun signedMoney(cents: Long, currency: String?, locale: Locale): String =
        signedAmount(cents) { money(it, currency, locale) }

    actual fun signedNumber(cents: Long, locale: Locale): String = signedAmount(cents) { number(it, locale) }

    actual fun number(cents: Long, locale: Locale): String = NumberFormat.getNumberInstance(locale.forFormatting()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(cents, 2))

    private fun dateStyle(locale: Locale) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale.forFormatting())

    actual fun calendarDate(isoDate: String, locale: Locale): String =
        runCatching { LocalDate.parse(isoDate).format(dateStyle(locale)) }.getOrDefault(isoDate)

    actual fun day(epochMillis: Long, locale: Locale): String = day(epochMillis, locale, ZoneId.systemDefault())

    /** [day] in [zone], for a test that must not depend on the machine's zone. */
    fun day(epochMillis: Long, locale: Locale, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dateStyle(locale))
}
