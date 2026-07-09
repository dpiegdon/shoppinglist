package org.p23q.shoppinglist.ui.theme

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
}
