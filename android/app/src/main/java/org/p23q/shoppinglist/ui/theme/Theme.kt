package org.p23q.shoppinglist.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Which color scheme [ShoppingListTheme] should render, following the system's dark/light setting. */
enum class ThemeVariant { LIGHT, DARK, DYNAMIC_LIGHT, DYNAMIC_DARK }

/**
 * Pure decision function, kept separate from the composable below so it is unit-testable
 * without an Android/Compose runtime. Dynamic color is only ever offered when the platform
 * supports it (API 31+, see [ShoppingListTheme]).
 */
fun resolveThemeVariant(darkTheme: Boolean, dynamicColorAvailable: Boolean): ThemeVariant = when {
    dynamicColorAvailable && darkTheme -> ThemeVariant.DYNAMIC_DARK
    dynamicColorAvailable && !darkTheme -> ThemeVariant.DYNAMIC_LIGHT
    darkTheme -> ThemeVariant.DARK
    else -> ThemeVariant.LIGHT
}

// Brand palette (T-51): seeded from the logo purple (#863BFF) with the icon's lavender (#EDE6FF)
// as its container, and the logo's blue (#47BFFF) informing the tertiary accent. Only the primary/
// tertiary families are overridden; the Material baseline (already purple-family) fills the rest.
private val BrandPurple = Color(0xFF863BFF)
private val BrandLavender = Color(0xFFEDE6FF)

private val LightColors = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = BrandLavender,
    onPrimaryContainer = Color(0xFF2A0A56),
    tertiary = Color(0xFF00658F),
    tertiaryContainer = Color(0xFFC7E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCDB0FF),
    onPrimary = Color(0xFF44148F),
    primaryContainer = Color(0xFF6A24D0),
    onPrimaryContainer = BrandLavender,
    tertiary = Color(0xFF85CFFF),
    tertiaryContainer = Color(0xFF004C6D),
    onTertiaryContainer = Color(0xFFC7E7FF),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
)

@Composable
fun ShoppingListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default (T-51): dynamic color follows the wallpaper on Android 12+, which would
    // override the brand palette everywhere it matters. Kept as an opt-in parameter.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // The Build.VERSION.SDK_INT check must guard the dynamicColorScheme calls directly at their
    // call site so lint's NewApi detector can see it; it can't follow the check through
    // resolveThemeVariant's dynamicColorAvailable parameter.
    val dynamicColorAvailable = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val variant = resolveThemeVariant(darkTheme, dynamicColorAvailable)
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        when (variant) {
            ThemeVariant.DYNAMIC_DARK -> dynamicDarkColorScheme(context)
            ThemeVariant.DYNAMIC_LIGHT -> dynamicLightColorScheme(context)
            ThemeVariant.DARK -> DarkColors
            ThemeVariant.LIGHT -> LightColors
        }
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
