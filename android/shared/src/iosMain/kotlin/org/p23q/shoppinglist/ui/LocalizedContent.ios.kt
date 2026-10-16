package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.p23q.shoppinglist.core.AppLocale

/** The language is [LocalAppLocale], which [appLocale] reads; the layout direction follows it. */
@Composable
actual fun LocalizedContent(locale: AppLocale, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides if (locale.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalAppLocale provides locale,
        content = content,
    )
}
