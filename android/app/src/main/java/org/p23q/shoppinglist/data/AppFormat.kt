package org.p23q.shoppinglist.data

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Amounts and dates in the app's chosen language (T-180, T-187), as the web's lib/format.ts does:
 * 64,00 € in German, €64.00 in English, and dates as each language writes them.
 *
 * Display only. Input fields keep the plain 64.00 / 2026-09-17 forms they parse, so what a user
 * types is never reinterpreted by their language setting.
 */
object AppFormat {

    /** Only an ISO code gets currency formatting; a free-text label (a list's own unit) does not. */
    private val isoCurrency = Regex("^[A-Z]{3}$")

    /** 64,00 € / €64.00 — or "12,00 pizza slices" when the label is not a currency code. */
    fun money(cents: Long, currency: String?, locale: Locale): String {
        val label = currency?.trim().orEmpty()
        val code = if (isoCurrency.matches(label)) runCatching { Currency.getInstance(label) }.getOrNull() else null
        if (code != null) {
            // The currency's own precision (none for yen), set explicitly: java.text's setCurrency
            // does not change the fraction digits. An amount that really has cents keeps them
            // rather than being rounded away.
            val format = NumberFormat.getCurrencyInstance(locale).apply { this.currency = code }
            val digits = if (cents % 100 != 0L) 2 else code.defaultFractionDigits.coerceAtLeast(0)
            format.minimumFractionDigits = digits
            format.maximumFractionDigits = digits
            return format.format(BigDecimal.valueOf(cents, 2))
        }
        val number = number(cents, locale)
        return if (label.isNotEmpty()) "$number $label" else number
    }

    /**
     * [money] with a "+" on a credit: +64,00 €, and -32,00 € unchanged (T-241). A balance's colour
     * is never its only signal, so the sign carries the same meaning for a reader who cannot tell
     * the green from the red.
     *
     * The plus goes exactly where the language puts its minus, which is why it is found by
     * formatting the amount both ways rather than pasted onto the front: Arabic writes its sign
     * after a bidi mark, and a "+" glued to the front of an RTL amount lands on the wrong end of
     * the line. java.text has no sign-display option and no plus-sign accessor to ask instead.
     */
    fun signedMoney(cents: Long, currency: String?, locale: Locale): String {
        val positive = money(cents, currency, locale)
        if (cents <= 0L) return positive
        val negative = money(-cents, currency, locale)
        val prefix = negative.commonPrefixWith(positive).length
        val suffix = negative.commonSuffixWith(positive).length
        // What the negative rendering adds is the sign, mark and all.
        if (prefix + suffix >= negative.length) return "+$positive"
        val minus = negative.substring(prefix, negative.length - suffix)
        val plus = minus.replace('-', '+').replace('−', '+')
        // A language that brackets its negatives instead of signing them: fall back to a plain plus.
        if (plus == minus) return "+$positive"
        return negative.substring(0, prefix) + plus + negative.substring(negative.length - suffix)
    }

    /** 64,00 / 64.00: the number alone, always with two decimals. */
    fun number(cents: Long, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(BigDecimal.valueOf(cents, 2))

    private fun dateStyle(locale: Locale) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    /** A calendar date (YYYY-MM-DD, no time, no zone) as the language writes it. */
    fun calendarDate(isoDate: String, locale: Locale): String =
        runCatching { LocalDate.parse(isoDate).format(dateStyle(locale)) }.getOrDefault(isoDate)

    /** The day a moment falls on, locally, in the same style as [calendarDate]. */
    fun day(epochMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dateStyle(locale))
}
