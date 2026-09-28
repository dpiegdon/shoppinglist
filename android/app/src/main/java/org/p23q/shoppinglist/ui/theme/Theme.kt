package org.p23q.shoppinglist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Which colour scheme [ShoppingListTheme] renders: the brand scheme, light or dark. */
enum class ThemeVariant { LIGHT, DARK }

fun themeVariant(darkTheme: Boolean): ThemeVariant = if (darkTheme) ThemeVariant.DARK else ThemeVariant.LIGHT

// The Material schemes, built from BrandColors.kt: change a colour there, not here. Every slot a
// component reads is set, so none falls back to Material's purple-tinted baseline:
// - background and surface are the flat background, and surfaceTint is the surface itself, so
//   elevation never tints a card or dialog with the accent;
// - cards, dialogs, the drawer and menus sit on the neutral secondary surface;
// - the selected state (secondaryContainer: the drawer's current entry, a selected chip) is the
//   accent, as on the web;
// - tertiary is the highlighted text, read through [accentText].
// The error slots keep Material's reds: a meaning colour, not a brand one.
private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = OnAccentLight,
    primaryContainer = AccentLight,
    onPrimaryContainer = OnAccentLight,
    inversePrimary = AccentLight,
    secondary = MutedLight,
    onSecondary = BackgroundLight,
    secondaryContainer = AccentLight,
    onSecondaryContainer = OnAccentLight,
    tertiary = AccentTextLight,
    onTertiary = BackgroundLight,
    tertiaryContainer = SurfaceLight,
    onTertiaryContainer = AccentTextLight,
    background = BackgroundLight,
    onBackground = ForegroundLight,
    surface = BackgroundLight,
    onSurface = ForegroundLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = MutedLight,
    surfaceTint = BackgroundLight,
    inverseSurface = ForegroundLight,
    inverseOnSurface = BackgroundLight,
    outline = MutedLight,
    outlineVariant = BorderLight,
    surfaceBright = BackgroundLight,
    surfaceDim = BackgroundLight,
    surfaceContainerLowest = BackgroundLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = AccentDark,
    onPrimaryContainer = OnAccentDark,
    inversePrimary = AccentDark,
    secondary = MutedDark,
    onSecondary = BackgroundDark,
    secondaryContainer = AccentDark,
    onSecondaryContainer = OnAccentDark,
    tertiary = AccentTextDark,
    onTertiary = BackgroundDark,
    tertiaryContainer = SurfaceDark,
    onTertiaryContainer = AccentTextDark,
    background = BackgroundDark,
    onBackground = ForegroundDark,
    surface = BackgroundDark,
    onSurface = ForegroundDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = MutedDark,
    surfaceTint = BackgroundDark,
    inverseSurface = ForegroundDark,
    inverseOnSurface = BackgroundDark,
    outline = MutedDark,
    outlineVariant = BorderDark,
    surfaceBright = BackgroundDark,
    surfaceDim = BackgroundDark,
    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = SurfaceDark,
)

/** The Material colour scheme for [variant], built from BrandColors.kt. */
fun brandColorScheme(variant: ThemeVariant): ColorScheme = when (variant) {
    ThemeVariant.LIGHT -> LightColors
    ThemeVariant.DARK -> DarkColors
}

/**
 * The highlighted text (links, the "due today" date, group headings, the open-item count): the
 * scheme's tertiary slot, named for what it is. Text in the accent colour uses this, not
 * `primary`, which in the light scheme is too light to read on white.
 */
val ColorScheme.accentText: Color get() = tertiary

// The green a credit is written in (T-241): a fixed light/dark pair rather than a colour-scheme
// slot, because it carries a meaning — red owed, green owing to you — and a meaning is not a brand
// colour. The same two values are the web's --color-positive in web/src/index.css;
// web/src/lib/balanceColors.test.ts reads this file and fails if the two clients drift apart.
// Red stays colorScheme.error.
val BalancePositiveLight = Color(0xFF15803D)
val BalancePositiveDark = Color(0xFF4ADE80)

/** The green for the theme being rendered. */
fun positiveBalanceColor(variant: ThemeVariant): Color = when (variant) {
    ThemeVariant.DARK -> BalancePositiveDark
    ThemeVariant.LIGHT -> BalancePositiveLight
}

/**
 * [positiveBalanceColor] for the theme in scope, so a composable showing a balance need not know
 * whether the system is in dark mode. [ShoppingListTheme] provides it.
 */
val LocalPositiveBalanceColor = staticCompositionLocalOf { BalancePositiveLight }

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
)

/**
 * The app's theme: always the brand scheme of BrandColors.kt, light or dark. There is no
 * wallpaper-based (dynamic) colour: the app looks the same on every phone.
 */
@Composable
fun ShoppingListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val variant = themeVariant(darkTheme)
    CompositionLocalProvider(LocalPositiveBalanceColor provides positiveBalanceColor(variant)) {
        MaterialTheme(
            colorScheme = brandColorScheme(variant),
            typography = AppTypography,
            content = content,
        )
    }
}
