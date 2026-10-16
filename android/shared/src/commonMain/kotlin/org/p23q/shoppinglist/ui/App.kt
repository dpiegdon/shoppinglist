package org.p23q.shoppinglist.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.deviceLocale
import org.p23q.shoppinglist.ui.theme.ShoppingListTheme

/**
 * The whole app as the platform's window shows it: the chosen language, the chosen theme, and the
 * navigation from [startDestination] (see [coldStartDestination]). Android's MainActivity sets it as
 * its content; the iOS app's view controller hosts it the same way.
 */
@Composable
fun TuppuApp(
    startDestination: String,
    themePreferenceStore: ThemePreferenceStore = koinInject(),
    localePreferenceStore: LocalePreferenceStore = koinInject(),
) {
    val themePreference by themePreferenceStore.theme.collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    // Seeded with the device language rather than a fixed default, so the very first frame is
    // already in the right language for a user who has never chosen one — an English flash before
    // the stored value arrives would be visible on every cold start.
    val locale by localePreferenceStore.effective.collectAsStateWithLifecycle(initialValue = deviceLocale())
    // Outermost, so the theme and every screen below resolve strings — and layout direction — in
    // the chosen language (T-111/T-126).
    LocalizedContent(locale) {
        ShoppingListTheme(darkTheme = darkTheme) {
            // Android's base window theme (themes.xml) is a fixed light theme, not day/night — so
            // without this Surface, dark mode paints the dark palette's light-on-dark text over the
            // window's still-white background ("light gray on white"). Surface is the one
            // composable that actually fills the screen with colorScheme.background.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                ShoppingListNavHost(startDestination = startDestination)
            }
        }
    }
}
