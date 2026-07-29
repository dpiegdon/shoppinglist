package org.p23q.shoppinglist.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import org.p23q.shoppinglist.data.AppLocale

/**
 * Applies the chosen UI language to everything composed inside (T-111).
 *
 * Why this and not `AppCompatDelegate.setApplicationLocales`: that is the platform-sanctioned
 * per-app language API and would also register the choice in Android's system per-app-language
 * screen, but it requires pulling `androidx.appcompat` — and its whole theming stack — into what
 * is otherwise a pure Compose/Material3 app. This approach needs no new dependency and, more
 * usefully, gives [LocalLayoutDirection] directly, which is exactly the hook RTL needs for Arabic
 * (T-126): selecting Arabic mirrors the layout in the same recomposition that changes the text.
 *
 * The trade-off is real and worth stating: this only reaches strings resolved INSIDE composition.
 * Anything resolved outside it — notifications, WorkManager — must localize itself, which is what
 * [localizedContext] below exists for. The known case is the collaborator-change notification.
 *
 * `LocalContext` is overridden, not just `LocalConfiguration`, because `stringResource` resolves
 * against the context's resources; providing only the configuration would change layout direction
 * while leaving every string in the system language.
 */
@Composable
fun LocalizedContent(locale: AppLocale, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val localized = remember(context, locale) { localizedContext(context, locale) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalLayoutDirection provides if (locale.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalAppLocale provides locale,
        content = content,
    )
}

/**
 * The language [LocalizedContent] is currently applying, readable from anywhere inside it.
 *
 * This exists because [LocalContext] cannot be trusted to survive a window boundary, but a custom
 * CompositionLocal can — see [LocalizedOverlay]. Defaults to [AppLocale.ENGLISH] so a composable
 * previewed or unit-tested outside a provider still renders.
 */
val LocalAppLocale = staticCompositionLocalOf { AppLocale.ENGLISH }

/**
 * Re-applies the chosen language inside a dialog or popup (T-131).
 *
 * A Compose `Dialog`/`Popup` does not compose into its caller's window. It builds a new one from
 * `LocalView.current.context` — the Activity — and that window's ComposeView re-provides the
 * Android composition locals (`LocalContext`, `LocalConfiguration`, `LocalResources`) from that
 * context on the way in. So [LocalizedContent]'s override is discarded at the window boundary and
 * every `stringResource` inside resolves in the SYSTEM language, however the app is set.
 *
 * Custom CompositionLocals like [LocalAppLocale] are NOT overwritten that way — they propagate
 * into the dialog's subcomposition normally — so the locale is still readable inside, and this
 * simply provides the Android locals again from it.
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

/**
 * A [Context] whose resources resolve in [locale], for code that runs OUTSIDE composition and so
 * cannot see [LocalizedContent] — notifications and background workers.
 *
 * Sets the layout direction on the configuration too, so a right-to-left language is handled
 * consistently by anything that inflates a view from this context rather than only by Compose.
 */
fun localizedContext(base: Context, locale: AppLocale): Context {
    val configuration = Configuration(base.resources.configuration).apply {
        val javaLocale = locale.toJavaLocale()
        setLocale(javaLocale)
        setLayoutDirection(javaLocale)
    }
    // ContextThemeWrapper + applyOverrideConfiguration, NOT createConfigurationContext.
    //
    // createConfigurationContext returns a bare ContextImpl, which is NOT a ContextWrapper — so
    // anything that walks up the context chain looking for the Activity dead-ends at it.
    // hiltViewModel() does exactly that walk, and since LocalizedContent overrides LocalContext
    // for the whole app, EVERY screen's `viewModel: X = hiltViewModel()` default blew up on the
    // first frame: "Expected an activity context for creating a HiltViewModelFactory but instead
    // found: android.app.ContextImpl". The app could not start.
    //
    // A ContextThemeWrapper keeps `base` reachable through getBaseContext(), so the walk still
    // finds the Activity, while applyOverrideConfiguration supplies the localized resources.
    return ContextThemeWrapper(base, /* themeResId = */ 0).apply {
        applyOverrideConfiguration(configuration)
    }
}
