package org.p23q.shoppinglist.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A text button's label is the highlighted-text colour, not the accent (T-327): Material draws it
 * in `primary`, which on white reads at 2.9:1, so every dialog's confirm and dismiss were faint.
 */
@RunWith(RobolectricTestRunner::class)
class TextButtonColourTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun labelColour(darkTheme: Boolean): Color {
        var label = Color.Unspecified
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = darkTheme) {
                TuppuTextButton(onClick = {}) {
                    label = LocalContentColor.current
                    Text("OK")
                }
            }
        }
        composeTestRule.waitForIdle()
        return label
    }

    @Test
    fun `a text button's label is the highlighted text in the light theme`() {
        assertEquals(AccentTextLight, labelColour(darkTheme = false))
    }

    @Test
    fun `a text button's label is the highlighted text in the dark theme`() {
        assertEquals(AccentTextDark, labelColour(darkTheme = true))
    }
}
