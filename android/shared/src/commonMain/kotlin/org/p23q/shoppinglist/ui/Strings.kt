package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getString as composeGetString

/*
 * The user-facing strings live here as Compose resources (Res.string.*). Every screen and every
 * notification reads them through the functions here, never through the library's own
 * stringResource/getString, for two reasons:
 *
 * - Language. The library picks the language from the process's default locale. The app's
 *   language is its own choice (LocalizedContent puts it into composition, the notifications get
 *   it from LocalePreferenceStore), and changing the process default for it would also change how
 *   Compose shapes all text. So the language is passed in explicitly here.
 * - Formatting. The library replaces %1$s/%1$d with the argument's toString(). These functions
 *   format in the string's own language ([formatString]), as Android's getString does, so a number
 *   in an Arabic sentence keeps its Arabic digits.
 */

/**
 * The Compose resource environment that picks [locale]'s strings. Only the language and region
 * matter to the resources here; none of them has a theme or density variant.
 *
 * The library builds an environment publicly only from the system's locale, so this calls its
 * constructor, which is internal to it (hence the suppression and the opt-in). A library upgrade
 * that changes the constructor fails to compile here, and StringsTest pins the behaviour.
 */
@OptIn(InternalResourceApi::class)
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
fun resourceEnvironment(locale: PlatformLocale): ResourceEnvironment = ResourceEnvironment(
    LanguageQualifier(locale.resourceLanguage()),
    RegionQualifier(locale.resourceRegion()),
    ThemeQualifier.LIGHT,
    DensityQualifier.MDPI,
)

/** [resource] in [locale], with [formatArgs] filled in — for code outside composition. */
suspend fun getString(locale: PlatformLocale, resource: StringResource, vararg formatArgs: Any): String {
    val template = composeGetString(resourceEnvironment(locale), resource)
    return if (formatArgs.isEmpty()) template else formatString(locale, template, *formatArgs)
}

/** [resource] in the language the app is showing. */
@Composable
fun stringResource(resource: StringResource): String {
    val locale = appLocale()
    // Blocking, as the library's own stringResource is on Android: the text is there on the
    // first frame, never an empty string that fills in a frame later.
    return remember(resource, locale) { runBlocking { getString(locale, resource) } }
}

/** [resource] in the language the app is showing, with [formatArgs] filled in. */
@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String =
    formatString(appLocale(), stringResource(resource), *formatArgs)
