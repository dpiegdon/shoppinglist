package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.p23q.shoppinglist.core.AppLocale
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.countryCode
import platform.Foundation.languageCode

actual typealias PlatformLocale = NSLocale

actual fun AppLocale.toPlatformLocale(): PlatformLocale = NSLocale(localeIdentifier = tag)

/** iOS has no configuration to carry the language, so it is read from [LocalAppLocale]. */
@Composable
@ReadOnlyComposable
actual fun appLocale(): PlatformLocale = LocalAppLocale.current.toPlatformLocale()

internal actual fun PlatformLocale.resourceLanguage(): String = languageCode

internal actual fun PlatformLocale.resourceRegion(): String = countryCode ?: ""

private val placeholder = Regex("""%(?:(\d+)\$)?([sd%])""")

/**
 * The placeholders the strings use: `%1$s`, `%1$d`, their unnumbered forms and `%%`. A number is
 * written with [locale]'s digits.
 */
actual fun formatString(locale: PlatformLocale, template: String, vararg args: Any): String {
    var next = 0
    return placeholder.replace(template) { match ->
        val (position, conversion) = match.destructured
        if (conversion == "%") return@replace "%"
        val index = if (position.isNotEmpty()) position.toInt() - 1 else next++
        val arg = args.getOrNull(index) ?: return@replace match.value
        when {
            conversion == "d" && (arg is Int || arg is Long) -> {
                val formatter = NSNumberFormatter().apply {
                    this.locale = locale
                    usesGroupingSeparator = false
                }
                formatter.stringFromNumber(NSNumber(longLong = (arg as Number).toLong())) ?: arg.toString()
            }
            else -> arg.toString()
        }
    }
}

private fun isStrongRtl(c: Char): Boolean = c in '֐'..'ࣿ' || c in 'יִ'..'﷿' || c in 'ﹰ'..'﻿'

/** First-strong isolation around text that has right-to-left letters; other text is unchanged. */
actual fun bidiWrap(text: String): String = if (text.any(::isStrongRtl)) "⁨$text⁩" else text
