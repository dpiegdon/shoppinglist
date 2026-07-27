package org.p23q.shoppinglist.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
        content = content,
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
    return base.createConfigurationContext(configuration)
}
