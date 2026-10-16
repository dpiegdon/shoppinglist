package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.compose.resources.getString as composeGetString

/*
 * The user-facing strings live in :shared as Compose resources (Res.string.*). Every screen and
 * every notification reads them through the functions here, never through the library's own
 * stringResource/getString, for two reasons:
 *
 * - Language. The library picks the language from the process's default locale. The app's
 *   language is its own choice (LocalizedContent puts it on LocalConfiguration, posters get it from
 *   LocalePreferenceStore), and changing the process default for it would also change how
 *   Compose shapes all text. So the language is passed in explicitly here.
 * - Formatting. The library replaces %1$s/%1$d with the argument's toString(). These functions
 *   format with String.format in the string's own language, as Android's getString does, so a
 *   number in an Arabic sentence keeps its Arabic digits.
 */

private val environments = ConcurrentHashMap<Locale, ResourceEnvironment>()
private val environmentLock = Any()

/**
 * The Compose resource environment that picks [locale]'s strings.
 *
 * The library builds one only from the process default locale (its constructor is internal), so
 * this sets that default for the instant it asks, restores it, and keeps the result: a language
 * is looked up once per process. Only the language and region matter to the resources here; none
 * of them has a theme or density variant.
 */
fun resourceEnvironment(locale: Locale): ResourceEnvironment = environments[locale]
    ?: synchronized(environmentLock) {
        environments.getOrPut(locale) {
            val default = Locale.getDefault()
            val display = Locale.getDefault(Locale.Category.DISPLAY)
            val format = Locale.getDefault(Locale.Category.FORMAT)
            try {
                Locale.setDefault(locale)
                getSystemResourceEnvironment()
            } finally {
                Locale.setDefault(default)
                Locale.setDefault(Locale.Category.DISPLAY, display)
                Locale.setDefault(Locale.Category.FORMAT, format)
            }
        }
    }

/** [resource] in [locale], with [formatArgs] filled in — for code outside composition. */
suspend fun getString(locale: Locale, resource: StringResource, vararg formatArgs: Any): String {
    val template = composeGetString(resourceEnvironment(locale), resource)
    return if (formatArgs.isEmpty()) template else String.format(locale, template, *formatArgs)
}

/** The language composition is showing: the one [LocalizedContent] put on the configuration. */
@Composable
@ReadOnlyComposable
private fun compositionLocale(): Locale = LocalConfiguration.current.locales[0]

/** [resource] in the language the app is showing. */
@Composable
fun stringResource(resource: StringResource): String {
    val locale = compositionLocale()
    // Blocking, as the library's own stringResource is on Android: the text is there on the
    // first frame, never an empty string that fills in a frame later.
    return remember(resource, locale) { runBlocking { getString(locale, resource) } }
}

/** [resource] in the language the app is showing, with [formatArgs] filled in. */
@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String =
    String.format(compositionLocale(), stringResource(resource), *formatArgs)
