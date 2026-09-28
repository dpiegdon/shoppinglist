package org.p23q.shoppinglist.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    @Test
    fun `the system's dark or light setting picks the dark or light brand scheme`() {
        assertEquals(ThemeVariant.DARK, themeVariant(darkTheme = true))
        assertEquals(ThemeVariant.LIGHT, themeVariant(darkTheme = false))
    }

    @Test
    fun `the brand colours are the scheme decided for both clients (T-324)`() {
        // The web's tokens in web/src/index.css hold the same values, and a web test reads this
        // file; this one catches a hex edited here without opening the ticket's table.
        assertEquals(Color(0xFFFFFFFF), BackgroundLight)
        assertEquals(Color(0xFF000000), BackgroundDark)
        assertEquals(Color(0xFF1A1A1E), ForegroundLight)
        assertEquals(Color(0xFFF2F2F4), ForegroundDark)
        assertEquals(Color(0xFF5A97FF), AccentLight)
        assertEquals(Color(0xFF5A97FF), AccentDark)
        assertEquals(Color(0xFF126BFF), AccentTextLight)
        assertEquals(Color(0xFF5A97FF), AccentTextDark)
        assertEquals(Color(0xFFFFFFFF), OnAccentLight)
        assertEquals(Color(0xFF0B1220), OnAccentDark)
    }

    private fun assertBuiltFrom(
        variant: ThemeVariant,
        background: Color,
        foreground: Color,
        accent: Color,
        accentText: Color,
        onAccent: Color,
        muted: Color,
        border: Color,
        surface: Color,
    ) {
        val scheme = brandColorScheme(variant)
        // Buttons, checkmarks, switches and the selected state are the accent.
        assertEquals(accent, scheme.primary)
        assertEquals(onAccent, scheme.onPrimary)
        assertEquals(accent, scheme.secondaryContainer)
        assertEquals(onAccent, scheme.onSecondaryContainer)
        // Highlighted text.
        assertEquals(accentText, scheme.accentText)
        // Flat: the background and the surface are one colour, and elevation adds no tint.
        assertEquals(background, scheme.background)
        assertEquals(background, scheme.surface)
        assertEquals(scheme.surface, scheme.surfaceTint)
        assertEquals(foreground, scheme.onBackground)
        assertEquals(foreground, scheme.onSurface)
        // Neutral greys, not Material's purple-tinted baseline.
        assertEquals(muted, scheme.onSurfaceVariant)
        assertEquals(border, scheme.outlineVariant)
        assertEquals(surface, scheme.surfaceVariant)
        listOf(
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        ).forEach { assertEquals(surface, it) }
    }

    @Test
    fun `the light Material scheme is built from the light brand colours`() {
        assertBuiltFrom(
            ThemeVariant.LIGHT, BackgroundLight, ForegroundLight, AccentLight, AccentTextLight,
            OnAccentLight, MutedLight, BorderLight, SurfaceLight,
        )
    }

    @Test
    fun `the dark Material scheme is built from the dark brand colours`() {
        assertBuiltFrom(
            ThemeVariant.DARK, BackgroundDark, ForegroundDark, AccentDark, AccentTextDark,
            OnAccentDark, MutedDark, BorderDark, SurfaceDark,
        )
    }

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun assertReadable(name: String, ink: Color, paper: Color) {
        val (lighter, darker) = listOf(luminance(ink), luminance(paper)).sortedDescending()
        val ratio = (lighter + 0.05) / (darker + 0.05)
        assertTrue("$name is $ratio:1, below WCAG AA's 4.5:1", ratio >= 4.5)
    }

    @Test
    fun `highlighted text and plain text clear WCAG AA on the background`() {
        for (variant in ThemeVariant.entries) {
            val scheme = brandColorScheme(variant)
            assertReadable("$variant highlighted text", scheme.accentText, scheme.background)
            assertReadable("$variant text", scheme.onBackground, scheme.background)
            assertReadable("$variant muted text", scheme.onSurfaceVariant, scheme.background)
        }
    }

    @Test
    fun `the balance green follows the light or dark theme`() {
        assertEquals(BalancePositiveLight, positiveBalanceColor(ThemeVariant.LIGHT))
        assertEquals(BalancePositiveDark, positiveBalanceColor(ThemeVariant.DARK))
    }

    @Test
    fun `the balance green is the web's --color-positive pair`() {
        // web/src/lib/balanceColors.test.ts checks the same pairing from the other side; this one
        // catches a hex edited here without opening the web's index.css.
        assertEquals(Color(0xFF15803D), BalancePositiveLight)
        assertEquals(Color(0xFF4ADE80), BalancePositiveDark)
    }
}
