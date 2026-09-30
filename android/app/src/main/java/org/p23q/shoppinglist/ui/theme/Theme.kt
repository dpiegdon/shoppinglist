package org.p23q.shoppinglist.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

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
// - tertiary is the highlighted text, read through [accentText]. tertiaryContainer is the card
//   surface, and Material's contentColorFor matches it before surfaceContainerHighest, so its
//   onTertiaryContainer is the plain text: every card writes in plain text, not the accent
//   (T-337).
// The reds (T-333): a meaning colour, not a brand one — errors, overdue dates, the fill of every
// button that destroys something. The same values are the web's --color-danger, --color-danger-text
// and --color-danger-bg; web/src/lib/brandColors.test.ts reads them here and fails on drift. Text
// on the red is white on the light red and near-black on the dark one, where white read at 2.8:1.
val ErrorLight = Color(0xFFDC2626)
val ErrorDark = Color(0xFFF87171)
val OnErrorLight = Color(0xFFFFFFFF)
val OnErrorDark = Color(0xFF1A0505)
val ErrorContainerLight = Color(0xFFFEF2F2)
val ErrorContainerDark = Color(0xFF2A1414)

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
    onTertiaryContainer = ForegroundLight,
    background = BackgroundLight,
    onBackground = ForegroundLight,
    surface = BackgroundLight,
    onSurface = ForegroundLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = MutedLight,
    surfaceTint = BackgroundLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorLight,
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
    onTertiaryContainer = ForegroundDark,
    background = BackgroundDark,
    onBackground = ForegroundDark,
    surface = BackgroundDark,
    onSurface = ForegroundDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = MutedDark,
    surfaceTint = BackgroundDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorDark,
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

/**
 * A text button's colours: its label in [accentText] (T-327). Material draws it in `primary`, the
 * accent, which on white reads at 2.9:1, and Material 3 has no theme slot for it.
 */
@Composable
fun textButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.accentText)

/**
 * The app's text button: Material's, with [textButtonColors]. Every text button is this one, the
 * dialogs' confirm and dismiss buttons included; ThemeTest fails on a Material `TextButton` used
 * anywhere else.
 */
@Composable
fun TuppuTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = textButtonColors(), content = content)

/**
 * The app's filled button (T-334). Disabled, it keeps its own colour at half strength, as the web's
 * `.btn:disabled { opacity: 0.5 }` does, instead of Material's flat grey. Pass `colors` built by
 * [filledButtonColors] (the default) or `dangerButtonColors()`.
 */
@Composable
fun TuppuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = filledButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, contentPadding = contentPadding, content = content)

/** A filled button's colours in [containerColor]/[contentColor], disabled at half strength (T-334). */
@Composable
fun filledButtonColors(
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = containerColor,
    contentColor = contentColor,
    disabledContainerColor = containerColor.copy(alpha = 0.5f),
    disabledContentColor = contentColor,
)

// The green a credit is written in (T-241): a fixed light/dark pair rather than a colour-scheme
// slot, because it carries a meaning — red owed, green owing to you — and a meaning is not a brand
// colour. The same two values are the web's --color-positive in web/src/index.css;
// web/src/lib/balanceColors.test.ts reads this file and fails if the two clients drift apart.
// Red stays colorScheme.error.
val BalancePositiveLight = Color(0xFF15803D)
val BalancePositiveDark = Color(0xFF4ADE80)

// The admin's registration switch (T-112): a green track when new accounts are allowed, the error
// red when not, and a white knob, the same in both themes. Meaning colours too; the web's
// --color-switch-on and --color-switch-knob, which web/src/lib/brandColors.test.ts pins to these.
val RegistrationSwitchOn = Color(0xFF2E7D32)
val RegistrationSwitchKnob = Color(0xFFFFFFFF)

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
    SystemBarIcons(darkTheme)
    CompositionLocalProvider(LocalPositiveBalanceColor provides positiveBalanceColor(variant)) {
        MaterialTheme(
            colorScheme = brandColorScheme(variant),
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * The status- and navigation-bar icons in the colour the app's theme needs, not the phone's.
 * enableEdgeToEdge() picks them from the phone's dark-mode setting, so with the app on Light and the
 * phone on Dark they were white on the flat white background, and black on black the other way.
 */
@Composable
private fun SystemBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/** The activity behind a possibly wrapped context (LocalizedContent wraps it for the locale). */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}


