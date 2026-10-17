package org.p23q.shoppinglist.ui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

/** Foundation's formatters, which follow the same CLDR data as Android's. */
actual object AppFormat {

    private fun amount(cents: Long) = NSDecimalNumber(mantissa = kotlin.math.abs(cents).toULong(), exponent = -2, isNegative = cents < 0)

    actual fun money(cents: Long, currency: String?, locale: NSLocale): String {
        val label = currency?.trim().orEmpty()
        if (isoCurrency.matches(label)) {
            val format = NSNumberFormatter().apply {
                numberStyle = NSNumberFormatterCurrencyStyle
                this.locale = locale.forFormatting()
                currencyCode = label
            }
            // The currency's own precision (none for yen); an amount that really has cents keeps them.
            val digits = if (cents % 100 != 0L) 2uL else format.maximumFractionDigits
            format.minimumFractionDigits = digits
            format.maximumFractionDigits = digits
            format.stringFromNumber(amount(cents))?.let { return it }
        }
        val number = number(cents, locale)
        return if (label.isNotEmpty()) "$number $label" else number
    }

    actual fun signedMoney(cents: Long, currency: String?, locale: NSLocale): String =
        signedAmount(cents) { money(it, currency, locale) }

    actual fun signedNumber(cents: Long, locale: NSLocale): String = signedAmount(cents) { number(it, locale) }

    actual fun number(cents: Long, locale: NSLocale): String = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        this.locale = locale.forFormatting()
        minimumFractionDigits = 2uL
        maximumFractionDigits = 2uL
    }.stringFromNumber(amount(cents)) ?: (cents / 100.0).toString()

    private fun mediumDate(locale: NSLocale, utc: Boolean) = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
        this.locale = locale.forFormatting()
        if (utc) timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    }

    actual fun calendarDate(isoDate: String, locale: NSLocale): String {
        val millis = isoDateToUtcMillis(isoDate) ?: return isoDate
        return mediumDate(locale, utc = true).stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
    }

    actual fun day(epochMillis: Long, locale: NSLocale): String =
        mediumDate(locale, utc = false).stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

actual fun shortDate(isoDate: String, locale: NSLocale, today: String): String {
    val millis = isoDateToUtcMillis(isoDate) ?: return isoDate
    val skeleton = if (today.take(4) == isoDate.take(4)) "MMMd" else "yMMMd"
    val format = NSDateFormatter().apply {
        this.locale = locale.forFormatting()
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        dateFormat = NSDateFormatter.dateFormatFromTemplate(skeleton, 0u, locale.forFormatting()) ?: "yyyy-MM-dd"
    }
    return format.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}
