package org.p23q.shoppinglist.ui.about

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.ui.update.UpdateStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T-224: what the About screen says, and the update block that moved here from settings. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AboutScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun installedVersion(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    @Test
    fun `shows the mark, the name, what Tuppu means, the version and the licence`() {
        composeTestRule.setContent { AboutScreen() }

        // The mark carries no contentDescription (it is decorative), so it is found by its tag.
        composeTestRule.onNodeWithTag("about-mark", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Tuppu").assertExists()
        composeTestRule
            .onNodeWithText("The Akkadian word for a clay tablet — the thing a list was pressed into.")
            .assertExists()
        // The app's own version, as the package manager reports it — not a literal in the source.
        composeTestRule.onNodeWithText("Version ${installedVersion()}").assertExists()
        composeTestRule.onNodeWithText("MIT, © 2026 David R. Piegdon").assertExists()
    }

    @Test
    fun `shows the name in cuneiform over its transliteration (T-225)`() {
        composeTestRule.setContent { AboutScreen() }

        // The sign is a tinted vector, not text: no phone carries a cuneiform font. It is the
        // name, so it has a content description rather than being decorative like the mark.
        composeTestRule
            .onNodeWithTag("about-cuneiform", useUnmergedTree = true)
            .assertExists()
            .assertContentDescriptionEquals("ṭuppu")
        // About, unlike the login screen, also spells the reading out under the sign.
        composeTestRule.onNodeWithText("ṭuppu").assertExists()
    }

    @Test
    fun `opening About fires the update check that opening Settings used to (T-149)`() {
        var checks = 0
        composeTestRule.setContent { AboutScreen(onOpened = { checks++ }) }
        composeTestRule.waitForIdle()

        assertEquals(1, checks)
    }

    @Test
    fun `the update block moved here from settings, switch and status line both (T-135, T-149)`() {
        var enabled by mutableStateOf(true)
        var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        composeTestRule.setContent {
            AboutScreen(
                updateStatus = status,
                autoCheckEnabled = enabled,
                onSetAutoCheckEnabled = { enabled = it },
            )
        }

        composeTestRule.onNodeWithText("App updates").assertExists()
        composeTestRule.onNodeWithText("Check for updates automatically").assertExists()
        // The switch is the only toggleable thing on this screen.
        composeTestRule.onNode(isToggleable()).assertIsOn()
        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(isToggleable()).assertIsOff()

        val lines = listOf(
            "Checking for updates…",
            "You have the latest version (1.14.0).",
            "Version 1.15.0 is available.",
            "Couldn't check for updates.",
        )
        // Nothing to say before a check, or with checking switched off.
        lines.forEach { composeTestRule.onNodeWithText(it).assertDoesNotExist() }

        for ((next, line) in listOf(
            UpdateStatus.Checking to lines[0],
            UpdateStatus.UpToDate("1.14.0") to lines[1],
            UpdateStatus.Available("1.15.0") to lines[2],
            UpdateStatus.Failed to lines[3],
        )) {
            status = next
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(line).assertExists()
            (lines - line).forEach { composeTestRule.onNodeWithText(it).assertDoesNotExist() }
        }
    }
}
