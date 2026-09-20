package org.p23q.shoppinglist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.ui.expense.balanceColor
import org.robolectric.RobolectricTestRunner

/**
 * The colours a balance is read by (T-241), as the theme actually hands them out: red owed, green
 * owing to you, grey square — and each legible where it is drawn. The web's counterpart is
 * web/src/lib/format.test.ts and web/src/lib/balanceColors.test.ts.
 */
@RunWith(RobolectricTestRunner::class)
class BalanceColorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Rendered(
        val credit: Color,
        val debt: Color,
        val square: Color,
        val background: Color,
        val surface: Color,
        val error: Color,
        val onSurfaceVariant: Color,
        val primary: Color,
    )

    /** One composition under the theme, reporting the colours a balance row would be given. */
    private fun render(darkTheme: Boolean, dynamicColor: Boolean = false): Rendered {
        lateinit var rendered: Rendered
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                rendered = Rendered(
                    credit = balanceColor(1),
                    debt = balanceColor(-1),
                    square = balanceColor(0),
                    background = MaterialTheme.colorScheme.background,
                    surface = MaterialTheme.colorScheme.surface,
                    error = MaterialTheme.colorScheme.error,
                    onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
                    primary = MaterialTheme.colorScheme.primary,
                )
            }
        }
        composeTestRule.waitForIdle()
        return rendered
    }

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(ink: Color, paper: Color): Double {
        val (lighter, darker) = listOf(luminance(ink), luminance(paper)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertReadable(name: String, ink: Color, paper: Color) {
        val ratio = contrast(ink, paper)
        assertTrue("$name is $ratio:1, below WCAG AA's 4.5:1", ratio >= 4.5)
    }

    @Test
    fun `the light theme writes a credit green, a debt red and a square balance grey`() {
        val rendered = render(darkTheme = false)

        assertEquals(BalancePositiveLight, rendered.credit)
        assertEquals(rendered.error, rendered.debt)
        assertEquals(rendered.onSurfaceVariant, rendered.square)
        // The brand colour is for headings and buttons; it must not double as a meaning.
        assertTrue("a credit should not be the theme's primary", rendered.credit != rendered.primary)
    }

    @Test
    fun `the dark theme writes a credit green, a debt red and a square balance grey`() {
        val rendered = render(darkTheme = true)

        assertEquals(BalancePositiveDark, rendered.credit)
        assertEquals(rendered.error, rendered.debt)
        assertEquals(rendered.onSurfaceVariant, rendered.square)
        assertTrue("a credit should not be the theme's primary", rendered.credit != rendered.primary)
    }

    @Test
    fun `every light-theme balance colour clears WCAG AA where it is drawn`() {
        val rendered = render(darkTheme = false)

        assertReadable("light green on the background", rendered.credit, rendered.background)
        assertReadable("light green on a card", rendered.credit, rendered.surface)
        assertReadable("light red on the background", rendered.debt, rendered.background)
        assertReadable("light red on a card", rendered.debt, rendered.surface)
    }

    @Test
    fun `every dark-theme balance colour clears WCAG AA where it is drawn`() {
        val rendered = render(darkTheme = true)

        assertReadable("dark green on the background", rendered.credit, rendered.background)
        assertReadable("dark green on a card", rendered.credit, rendered.surface)
        assertReadable("dark red on the background", rendered.debt, rendered.background)
        assertReadable("dark red on a card", rendered.debt, rendered.surface)
    }

    @Test
    fun `the green does not follow the wallpaper when dynamic colour is on`() {
        val rendered = render(darkTheme = false, dynamicColor = true)

        assertEquals(BalancePositiveLight, rendered.credit)
    }
}
