package org.p23q.shoppinglist.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {

    @Test
    fun `dark system theme without dynamic color support resolves to static dark scheme`() {
        assertEquals(
            ThemeVariant.DARK,
            resolveThemeVariant(darkTheme = true, dynamicColorAvailable = false),
        )
    }

    @Test
    fun `light system theme without dynamic color support resolves to static light scheme`() {
        assertEquals(
            ThemeVariant.LIGHT,
            resolveThemeVariant(darkTheme = false, dynamicColorAvailable = false),
        )
    }

    @Test
    fun `dark system theme with dynamic color support resolves to dynamic dark scheme`() {
        assertEquals(
            ThemeVariant.DYNAMIC_DARK,
            resolveThemeVariant(darkTheme = true, dynamicColorAvailable = true),
        )
    }

    @Test
    fun `light system theme with dynamic color support resolves to dynamic light scheme`() {
        assertEquals(
            ThemeVariant.DYNAMIC_LIGHT,
            resolveThemeVariant(darkTheme = false, dynamicColorAvailable = true),
        )
    }

    @Test
    fun `the balance green follows the light or dark theme`() {
        assertEquals(BalancePositiveLight, positiveBalanceColor(ThemeVariant.LIGHT))
        assertEquals(BalancePositiveDark, positiveBalanceColor(ThemeVariant.DARK))
    }

    @Test
    fun `the balance green stays fixed under dynamic color`() {
        // Meaning-carrying colour must not follow the wallpaper (T-241).
        assertEquals(BalancePositiveLight, positiveBalanceColor(ThemeVariant.DYNAMIC_LIGHT))
        assertEquals(BalancePositiveDark, positiveBalanceColor(ThemeVariant.DYNAMIC_DARK))
    }

    @Test
    fun `the balance green is the web's --color-positive pair`() {
        // web/src/lib/balanceColors.test.ts checks the same pairing from the other side; this one
        // catches a hex edited here without opening the web's index.css.
        assertEquals(Color(0xFF15803D), BalancePositiveLight)
        assertEquals(Color(0xFF4ADE80), BalancePositiveDark)
    }
}
