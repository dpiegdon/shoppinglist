package org.p23q.shoppinglist.ui

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.ui.accounts.localAccountRow
import org.robolectric.RobolectricTestRunner

/** How an account is named on screen (T-293): the local area by a string resource, never its stored label. */
@RunWith(RobolectricTestRunner::class)
class AccountNameTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the local area is named in the app's language, a server account by email and server`() {
        val local = localAccountRow(label = "Auf diesem Telefon")
        val server = testAccount(serverUrl = "https://lists.example.test/stage/", email = "me@example.com")
        val names = mutableListOf<String>()

        composeTestRule.setContent {
            names += accountName(local)
            names += accountLineText(local)
            names += accountName(server)
            names += accountLineText(server)
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("On this phone", "On this phone", "me@example.com", "me@example.com · lists.example.test/stage"), names.take(4))
    }
}
