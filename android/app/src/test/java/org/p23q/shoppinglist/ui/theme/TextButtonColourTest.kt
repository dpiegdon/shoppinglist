package org.p23q.shoppinglist.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    private fun labelColour(darkTheme: Boolean): Color = labelColour(darkTheme) { content ->
        TuppuTextButton(onClick = {}) { content() }
    }

    private fun labelColour(darkTheme: Boolean, button: @Composable (content: @Composable () -> Unit) -> Unit): Color {
        var label = Color.Unspecified
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = darkTheme) {
                button {
                    label = LocalContentColor.current
                    Text("OK")
                }
            }
        }
        composeTestRule.waitForIdle()
        return label
    }

    @Test
    fun `an outlined button's label is the plain text, as the web's btn-secondary (T-343)`() {
        assertEquals(ForegroundLight, labelColour(darkTheme = false) { content -> TuppuOutlinedButton(onClick = {}) { content() } })
    }

    @Test
    fun `an outlined button's label is the plain text in the dark theme too (T-343)`() {
        assertEquals(ForegroundDark, labelColour(darkTheme = true) { content -> TuppuOutlinedButton(onClick = {}) { content() } })
    }

    @Test
    fun `a disabled outlined button keeps the text colour at half strength (T-343)`() {
        val label = labelColour(darkTheme = false) { content -> TuppuOutlinedButton(onClick = {}, enabled = false) { content() } }
        assertEquals(ForegroundLight.copy(alpha = 0.5f), label)
    }

    @Test
    fun `the registration switch's knob on the red is the on-red colour, readable in the dark theme too (T-343)`() {
        var colors: SwitchColors? = null
        composeTestRule.setContent { ShoppingListTheme(darkTheme = true) { colors = registrationSwitchColors() } }
        composeTestRule.waitForIdle()
        assertEquals(OnErrorDark, colors!!.uncheckedThumbColor)
        assertEquals(ErrorDark, colors!!.uncheckedTrackColor)
        // On the green it stays the white knob the web pins.
        assertEquals(RegistrationSwitchKnob, colors!!.checkedThumbColor)
    }

    @Test
    fun `a destructive confirm's label is the red (T-343)`() {
        assertEquals(ErrorLight, labelColour(darkTheme = false) { content -> DangerTextButton(onClick = {}) { content() } })
    }

    @Test
    fun `a destructive confirm's label is the red in the dark theme too (T-343)`() {
        assertEquals(ErrorDark, labelColour(darkTheme = true) { content -> DangerTextButton(onClick = {}) { content() } })
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
