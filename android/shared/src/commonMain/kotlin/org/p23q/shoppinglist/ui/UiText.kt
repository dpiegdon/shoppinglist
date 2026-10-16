package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource

/**
 * A user-facing message that has not been turned into a String yet (T-111).
 *
 * ViewModels cannot call `stringResource` — it is a `@Composable` — and they must not resolve
 * strings in the system language either, because that ignores the in-app language override and
 * would leave every status message in the system language while the rest of the UI changed. So a
 * ViewModel names the message and the UI resolves it, at which point [LocalizedContent] has
 * already put the chosen locale into composition.
 *
 * There is no case for raw text any more. It existed for server error messages, which arrive in
 * English: they are now translated from the error's code by [ErrorText], and a failure without a
 * known code shows the screen's own message. Having no raw case is what keeps untranslated server
 * text from creeping back in.
 */
sealed interface UiText {
    /** A string resource (Res.string.*), with any format arguments. */
    data class Res(val resource: StringResource, val args: List<Any> = emptyList()) : UiText

    companion object {
        fun res(resource: StringResource, vararg args: Any): Res = Res(resource, args.toList())
    }
}

/** Resolve inside composition, honouring the chosen language via [LocalizedContent]. */
@Composable
fun UiText.asString(): String {
    val locale = appLocale()
    return remember(this, locale) { runBlocking { asString(locale) } }
}

/**
 * Resolve outside composition, in [locale] — the app's chosen language, from
 * LocalePreferenceStore, or this silently falls back to the system language, which is the exact
 * bug this type exists to prevent.
 *
 * An argument may itself be a [UiText] and is resolved first, so a message can be composed from
 * parts without any call site having to pre-render them: "Synced %1$s" takes "5 min ago" as an
 * argument, and both halves stay translatable independently. Without this the outer string would
 * have to be assembled by concatenation, which is precisely what a translator cannot reorder.
 */
suspend fun UiText.asString(locale: PlatformLocale): String = when (this) {
    is UiText.Res -> getString(
        locale,
        resource,
        *args.map {
            when (it) {
                is UiText -> it.asString(locale)
                // User-authored text dropped into a sentence reorders the surrounding punctuation
                // without isolation (T-126): an Arabic list name inside "…updates for \"%1$s\" on
                // this device." drags the quotes to the wrong side. bidiWrap adds isolates
                // only when the run's direction actually differs from the context's, so an all-LTR
                // app is byte-for-byte unchanged.
                //
                // Plain Text(userValue) does NOT need this — Compose resolves an unspecified
                // TextDirection from the content's first strong character already. Only values
                // embedded in a larger string are at risk.
                is String -> bidiWrap(it)
                else -> it
            }
        }.toTypedArray(),
    )
}
