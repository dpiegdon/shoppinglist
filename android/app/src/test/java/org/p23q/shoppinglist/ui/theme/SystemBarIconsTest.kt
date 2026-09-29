package org.p23q.shoppinglist.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The system bar icons follow the app's theme, whatever the phone's dark mode says (T-331). */
@RunWith(RobolectricTestRunner::class)
class SystemBarIconsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** As MainActivity does: the icons start out as the phone's dark mode sets them. */
    private fun edgeToEdgeLikeTheApp() {
        composeTestRule.runOnUiThread { composeTestRule.activity.enableEdgeToEdge() }
    }

    private fun lightStatusIcons(): Pair<Boolean, Boolean> {
        val activity = composeTestRule.activity
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
    }

    @Test
    @Config(qualifiers = "night")
    fun `the app on Light with the phone on Dark gets dark icons`() {
        edgeToEdgeLikeTheApp()
        composeTestRule.setContent { ShoppingListTheme(darkTheme = false) {} }
        composeTestRule.waitForIdle()

        val (status, navigation) = lightStatusIcons()
        assertTrue("dark status-bar icons on the white background", status)
        assertTrue("dark navigation-bar icons on the white background", navigation)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `the app on Dark with the phone on Light gets light icons`() {
        edgeToEdgeLikeTheApp()
        composeTestRule.setContent { ShoppingListTheme(darkTheme = true) {} }
        composeTestRule.waitForIdle()

        val (status, navigation) = lightStatusIcons()
        assertFalse("light status-bar icons on the black background", status)
        assertFalse("light navigation-bar icons on the black background", navigation)
    }
}
