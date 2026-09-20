package org.p23q.shoppinglist.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.update.AvailableUpdate
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T-244: the blocking notice shown while this app is too old for its server. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class UpdateRequiredScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `starts the check by itself — this one is not optional`() {
        var checks = 0
        composeTestRule.setContent { UpdateRequiredScreen(onOpened = { checks++ }) }
        composeTestRule.waitForIdle()

        assertEquals(1, checks)
        // Until it reports, the screen says it is checking rather than guessing at an answer.
        composeTestRule.onNodeWithText("Checking for updates…").assertExists()
    }

    @Test
    fun `offers the download the way the ordinary prompt does when there is one`() {
        val update = AvailableUpdate("3.1.0", "https://example.com/shoppinglist.apk")
        var downloaded: AvailableUpdate? = null
        composeTestRule.setContent {
            UpdateRequiredScreen(
                status = UpdateStatus.Available("3.1.0"),
                update = update,
                onDownload = { downloaded = it },
                currentVersion = "2.2.0",
            )
        }

        composeTestRule.onNodeWithText("Update required").assertExists()
        composeTestRule.onNodeWithText("The server has been updated. Install the new version to continue.").assertExists()
        composeTestRule.onNodeWithText("Version 3.1.0 of the app is available. You have 2.2.0.").assertExists()
        composeTestRule.onNodeWithTag("update-required-download").performClick()
        composeTestRule.waitForIdle()

        assertEquals(update, downloaded)
    }

    @Test
    fun `names the server's operator when there is nothing to install, and retries on demand`() {
        var status by mutableStateOf<UpdateStatus>(UpdateStatus.Failed)
        var retries = 0
        composeTestRule.setContent {
            UpdateRequiredScreen(status = status, onRetry = { retries++ })
        }

        val unavailable = "This server has no newer app to offer. Ask whoever runs it for an update."
        // A failed check and a server that simply has nothing newer are the same dead end to the
        // user: only whoever runs the server can resolve either.
        composeTestRule.onNodeWithText(unavailable).assertExists()
        status = UpdateStatus.UpToDate("2.2.0")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(unavailable).assertExists()

        composeTestRule.onNodeWithTag("update-required-retry").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, retries)
        // No download button while there is nothing to download.
        composeTestRule.onNodeWithTag("update-required-download").assertDoesNotExist()
    }

    @Test
    fun `does not offer a download it has no address for`() {
        // Available without the update itself would be a button that could only do nothing.
        composeTestRule.setContent {
            UpdateRequiredScreen(status = UpdateStatus.Available("3.1.0"), update = null)
        }

        composeTestRule.onNodeWithTag("update-required-download").assertIsNotEnabled()
    }
}
