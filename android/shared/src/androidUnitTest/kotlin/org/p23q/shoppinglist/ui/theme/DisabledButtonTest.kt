package org.p23q.shoppinglist.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.ui.dangerButtonColors
import org.robolectric.RobolectricTestRunner

/** A disabled filled button keeps its own colour at half strength, as the web's does (T-334). */
@RunWith(RobolectricTestRunner::class)
class DisabledButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun colours(dark: Boolean, pick: @androidx.compose.runtime.Composable () -> ButtonColors): Triple<ButtonColors, Color, Color> {
        lateinit var result: Triple<ButtonColors, Color, Color>
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = dark) {
                result = Triple(pick(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error)
            }
        }
        composeTestRule.waitForIdle()
        return result
    }

    @Test
    fun `a disabled button is the accent at half strength, its label unchanged`() {
        val (colors, accent, _) = colours(dark = false) { filledButtonColors() }
        assertEquals(accent.copy(alpha = 0.5f), colors.disabledContainerColor)
        assertEquals(colors.contentColor, colors.disabledContentColor)
    }

    @Test
    fun `a disabled delete button is the red at half strength, with its dark label in the dark`() {
        val (colors, _, red) = colours(dark = true) { dangerButtonColors() }
        assertEquals(red.copy(alpha = 0.5f), colors.disabledContainerColor)
        assertEquals(OnErrorDark, colors.contentColor)
        assertEquals(colors.contentColor, colors.disabledContentColor)
    }
}
