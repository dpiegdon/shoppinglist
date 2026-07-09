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

private val DarkColors = darkColorScheme()
private val LightColors = lightColorScheme()

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
)

@Composable
fun ShoppingListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
