package org.p23q.shoppinglist.ui

/**
 * Amounts and dates in the app's chosen language (T-180, T-187), as the web's lib/format.ts does:
 * 64,00 € in German, €64.00 in English, and dates as each language writes them. Each platform
 * formats with its own locale data: java.text and java.time on Android, Foundation on iOS.
 *
 * Display only. Input fields keep the plain 64.00 / 2026-09-17 forms they parse, so what a user
 * types is never reinterpreted by their language setting.
 */
expect object AppFormat {
    /**
     * 64,00 € / €64.00 — or "12,00 pizza slices" when the label is not a currency code. Only an
     * ISO code (three capitals) gets currency formatting, in the currency's own precision (none
     * for yen) unless the amount really has cents.
     */
    fun money(cents: Long, currency: String?, locale: PlatformLocale): String

    /**
     * [money] with a "+" on a credit: +64,00 €, and -32,00 € unchanged (T-241). A balance's colour
     * is never its only signal, so the sign carries the same meaning for a reader who cannot tell
     * the green from the red.
     */
    fun signedMoney(cents: Long, currency: String?, locale: PlatformLocale): String

    /**
     * [number] with a "+" on a positive: the settled figure on the balances screen (T-245), which
     * says which way the money went and so is meaningless without its sign.
     */
    fun signedNumber(cents: Long, locale: PlatformLocale): String

    /** 64,00 / 64.00: the number alone, always with two decimals. */
    fun number(cents: Long, locale: PlatformLocale): String

    /** A calendar date (YYYY-MM-DD, no time, no zone) as the language writes it, in the medium style. */
    fun calendarDate(isoDate: String, locale: PlatformLocale): String

    /** The day a moment falls on, in the device's zone, in the same style as [calendarDate]. */
    fun day(epochMillis: Long, locale: PlatformLocale): String
}

/** Only an ISO code gets currency formatting; a free-text label (a list's own unit) does not. */
internal val isoCurrency = Regex("^[A-Z]{3}$")

/**
 * [render]'s amount with a "+" on a positive. The plus goes exactly where the language puts its
 * minus, which is why it is found by rendering the amount both ways rather than pasted onto the
 * front: Arabic writes its sign after a bidi mark, and a "+" glued to the front of an RTL amount
 * lands on the wrong end of the line. The formatters have no sign-display option to ask instead.
 */
internal fun signedAmount(cents: Long, render: (Long) -> String): String {
    val positive = render(cents)
    if (cents <= 0L) return positive
    val negative = render(-cents)
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

/**
 * A calendar date (YYYY-MM-DD) in its shortest form, as the language writes it (T-323): "Oct 3" /
 * "3. Okt.", and with the year only when it is not [today]'s: "Jan 5, 2027". The web's
 * `shortDate` in lib/format.ts, tested with the same example dates. A month-and-day form is a CLDR
 * skeleton, which the platform resolves per language. Formatted at UTC midnight, so no zone moves
 * the day; anything that is not a date comes back as it is.
 */
expect fun shortDate(isoDate: String, locale: PlatformLocale, today: String): String
