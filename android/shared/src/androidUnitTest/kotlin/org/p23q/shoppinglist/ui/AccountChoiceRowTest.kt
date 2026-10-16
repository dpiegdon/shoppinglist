package org.p23q.shoppinglist.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.testAccount
import org.robolectric.RobolectricTestRunner

/** The one "which account" row (A7): two lines, plain text, a 48dp target that chooses. */
@RunWith(RobolectricTestRunner::class)
class AccountChoiceRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val account = testAccount(id = "a1", serverUrl = "https://lists.example.test/", accountId = "acct-a1")

    @Test
    fun `names the account and its server in the plain text colour, even in a muted dialog, at 48dp or more`() {
        var chosen = 0
        composeTestRule.setContent {
            InBrandColors {
                // A dialog's text slot draws in the muted colour; the choice must not.
                CompositionLocalProvider(LocalContentColor provides Color.Gray) {
                    AccountChoiceRow(account, onClick = { chosen++ }, modifier = Modifier.testTag("row"))
                }
            }
        }

        composeTestRule.onNodeWithTag("row").assertTextEquals("me@example.com", "https://lists.example.test/")
        composeTestRule.onNodeWithTag("row").assertHeightIsAtLeast(48.dp)
        val node = composeTestRule.onNodeWithText("me@example.com", useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals(sectionTestScheme.onSurface, results.first().layoutInput.style.color)

        composeTestRule.onNodeWithTag("row").performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun `with a selection it is one of a radio group`() {
        composeTestRule.setContent {
            AccountChoiceRow(account, onClick = {}, selected = true, modifier = Modifier.testTag("row"))
        }
        composeTestRule.onNodeWithTag("row").assertIsSelected()
    }
}
