package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.text.BidiFormatter
import org.p23q.shoppinglist.core.AppLocale
import java.util.Locale

actual typealias PlatformLocale = Locale

actual fun AppLocale.toPlatformLocale(): PlatformLocale = toJavaLocale()

/** The language LocalizedContent put on the configuration. */
@Composable
@ReadOnlyComposable
actual fun appLocale(): PlatformLocale = LocalConfiguration.current.locales[0]

internal actual fun PlatformLocale.resourceLanguage(): String = language

internal actual fun PlatformLocale.resourceRegion(): String = country

actual fun formatString(locale: PlatformLocale, template: String, vararg args: Any): String =
    String.format(locale, template, *args)

// BidiFormatter adds isolates only when the run's direction differs from the context's, so an
// all-LTR app is byte-for-byte unchanged.
actual fun bidiWrap(text: String): String = BidiFormatter.getInstance().unicodeWrap(text)
