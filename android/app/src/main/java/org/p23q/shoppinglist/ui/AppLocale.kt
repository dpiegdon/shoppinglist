package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The language the app is showing (T-180, T-187) — LocalizedContent puts the chosen one into the
 * configuration, so amounts and dates follow the in-app language choice, not only the phone's.
 */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]
