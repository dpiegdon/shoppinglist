package org.p23q.shoppinglist.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import org.p23q.shoppinglist.core.AppLocale

/**
 * Applies the chosen UI language to everything composed inside (T-111): the strings
 * ([stringResource] in Strings.kt reads the language from here), the amounts and dates
 * ([appLocale]), and [LocalLayoutDirection], which is exactly the hook RTL needs for Arabic
 * (T-126): selecting Arabic mirrors the layout in the same recomposition that changes the text.
 *
 * This only reaches strings resolved INSIDE composition. Anything resolved outside it —
 * notifications, background work — must name the language itself, as the notification posters do
 * by passing the stored choice to [getString].
 *
 * On Android the language goes onto `LocalConfiguration` and `LocalContext` as well, so whatever
 * reads the platform's resources or configuration agrees.
 */
@Composable
expect fun LocalizedContent(locale: AppLocale, content: @Composable () -> Unit)

/**
 * The language [LocalizedContent] is currently applying, readable from anywhere inside it.
 *
 * This exists because the platform's own locals cannot be trusted to survive a window boundary,
 * but a custom CompositionLocal can — see [LocalizedOverlay]. Defaults to [AppLocale.ENGLISH] so a
 * composable previewed or unit-tested outside a provider still renders.
 */
val LocalAppLocale = staticCompositionLocalOf { AppLocale.ENGLISH }

/**
 * Re-applies the chosen language inside a dialog or popup (T-131).
 *
 * A Compose `Dialog`/`Popup` does not compose into its caller's window. On Android it builds a new
 * one from `LocalView.current.context` — the Activity — and that window's ComposeView re-provides
 * the Android composition locals (`LocalContext`, `LocalConfiguration`, `LocalResources`) from
 * that context on the way in. So [LocalizedContent]'s override is discarded at the window boundary
 * and every `stringResource` inside resolves in the SYSTEM language, however the app is set.
 *
 * Custom CompositionLocals like [LocalAppLocale] are NOT overwritten that way — they propagate
 * into the dialog's subcomposition normally — so the locale is still readable inside, and this
 * simply provides the platform's locals again from it.
 *
 * Prefer [LocalizedAlertDialog] for an AlertDialog; use this directly for other overlays
 * (`DropdownMenu`, `ModalBottomSheet`, a raw `Dialog`), whose content has the same problem.
 */
@Composable
fun LocalizedOverlay(content: @Composable () -> Unit) {
    LocalizedContent(LocalAppLocale.current, content)
}

/**
 * [AlertDialog] with every content slot wrapped in [LocalizedOverlay].
 *
 * Wrapping the slots rather than the call is deliberate: the slots are what get composed inside
 * the dialog's own window, so that is where the language has to be restored. Dialogs should use
 * this instead of [AlertDialog] — a plain one silently reverts to the system language.
 */
@Composable
fun LocalizedAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { LocalizedOverlay(confirmButton) },
        modifier = modifier,
        dismissButton = dismissButton?.let { { LocalizedOverlay(it) } },
        icon = icon?.let { { LocalizedOverlay(it) } },
        title = title?.let { { LocalizedOverlay(it) } },
        text = text?.let { { LocalizedOverlay(it) } },
    )
}
