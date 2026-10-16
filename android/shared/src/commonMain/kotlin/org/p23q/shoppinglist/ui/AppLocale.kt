package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.p23q.shoppinglist.core.AppLocale

/**
 * The platform's own locale, which the strings are formatted in and amounts and dates are written
 * in: `java.util.Locale` on Android, `NSLocale` on iOS. Built from the app's [AppLocale] with
 * [toPlatformLocale], or read from composition with [appLocale].
 */
expect class PlatformLocale

/** The platform locale this language's tag denotes. */
expect fun AppLocale.toPlatformLocale(): PlatformLocale

/**
 * The language the app is showing (T-180, T-187) — LocalizedContent puts the chosen one into
 * composition, so amounts and dates follow the in-app language choice, not only the phone's.
 */
@Composable
@ReadOnlyComposable
expect fun appLocale(): PlatformLocale

/** The language code a Compose resource directory is chosen by: "pt" for pt-BR. */
internal expect fun PlatformLocale.resourceLanguage(): String

/** The region a Compose resource directory is chosen by: "BR" for pt-BR, empty for none. */
internal expect fun PlatformLocale.resourceRegion(): String

/**
 * [template] with its `%1$s` / `%1$d` placeholders filled from [args], numbers written as
 * [locale] writes them (Arabic digits in Arabic), as Android's getString does.
 */
expect fun formatString(locale: PlatformLocale, template: String, vararg args: Any): String

/**
 * [text] wrapped in Unicode directional isolates when its direction differs from the context's,
 * so user-authored text dropped into a sentence does not reorder the punctuation around it.
 */
expect fun bidiWrap(text: String): String
