package org.p23q.shoppinglist.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A user-facing message that has not been turned into a String yet (T-111).
 *
 * ViewModels cannot call `stringResource` — it is a `@Composable` — and they must not resolve
 * strings against the application context either, because that ignores the in-app language
 * override and would leave every status message in the system language while the rest of the UI
 * changed. So a ViewModel names the message and the UI resolves it, at which point
 * [LocalizedContent] has already put the chosen locale on `LocalContext`.
 *
 * [Raw] exists for text the app did not author: server error messages arrive already-formed over
 * the wire. Those stay English regardless of the UI language — the server has no localization, and
 * the wire contract's error CODES would be the way to fix that properly, one day. Keeping them a
 * distinct case rather than smuggling them through [Res] makes that limitation visible instead of
 * silently pretending they are translated.
 */
sealed interface UiText {
    /** Text the app did not author — currently only server-supplied messages. Not translated. */
    data class Raw(val value: String) : UiText

    /** A string resource, with any format arguments. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): Res = Res(id, args.toList())
    }
}

/** Resolve inside composition, honouring the chosen language via [LocalizedContent]. */
@Composable
fun UiText.asString(): String = asString(LocalContext.current)

/**
 * Resolve outside composition. [context] must already be localized — see [localizedContext] — or
 * this silently falls back to the system language, which is the exact bug this type exists to
 * prevent.
 *
 * An argument may itself be a [UiText] and is resolved first, so a message can be composed from
 * parts without any call site having to pre-render them: "Synced %1$s" takes "5 min ago" as an
 * argument, and both halves stay translatable independently. Without this the outer string would
 * have to be assembled by concatenation, which is precisely what a translator cannot reorder.
 */
fun UiText.asString(context: Context): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> context.getString(
        id,
        *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray(),
    )
}
