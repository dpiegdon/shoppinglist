package org.p23q.shoppinglist.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.p23q.shoppinglist.core.AppLocale

/**
 * Why this and not `AppCompatDelegate.setApplicationLocales`: that is the platform-sanctioned
 * per-app language API and would also register the choice in Android's system per-app-language
 * screen, but it requires pulling `androidx.appcompat` — and its whole theming stack — into what
 * is otherwise a pure Compose/Material3 app. This approach needs no new dependency and gives
 * [LocalLayoutDirection] directly.
 *
 * The app's strings ([stringResource] in Strings.kt) take their language from `LocalConfiguration`
 * ([appLocale]). `LocalContext` is overridden too, with a context whose resources carry the same
 * configuration, so whatever reads the platform's resources or configuration through the context
 * agrees.
 */
@Composable
actual fun LocalizedContent(locale: AppLocale, content: @Composable () -> Unit) {
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
 * A [Context] whose resources and configuration are in [locale]: what [LocalizedContent] provides
 * as `LocalContext`, and whose configuration it provides as `LocalConfiguration`.
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
    // LocalizedContent overrides LocalContext for the whole app, so every such walk below it
    // fails: with Hilt, whose view model factory made exactly that walk, EVERY screen's view
    // model default blew up on the first frame and the app could not start (T-129).
    //
    // A ContextThemeWrapper keeps `base` reachable through getBaseContext(), so the walk still
    // finds the Activity, while applyOverrideConfiguration supplies the localized resources.
    return ContextThemeWrapper(base, /* themeResId = */ 0).apply {
        applyOverrideConfiguration(configuration)
    }
}
