package org.p23q.shoppinglist.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppLocale
import org.robolectric.RobolectricTestRunner

/**
 * The chosen UI language has to reach inside dialogs too (T-131).
 *
 * A Compose `Dialog` is not composed into the same Android window as its caller: it hosts its own
 * ComposeView inside a real android.app.Dialog, and that view re-provides the Android composition
 * locals — LocalContext, LocalConfiguration, LocalResources — from ITS OWN context. Custom
 * CompositionLocals propagate into the dialog's subcomposition, but those platform-provided ones
 * get overwritten on the way in, so [LocalizedContent]'s override was silently discarded and every
 * `stringResource` inside a dialog resolved in the SYSTEM language instead of the chosen one.
 *
 * Asserted here on a bare AlertDialog rather than on a real screen, so the test pins the mechanism
 * rather than one dialog that happened to be reported.
 */
@RunWith(RobolectricTestRunner::class)
class LocalizedDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `strings inside a LocalizedAlertDialog resolve in the chosen language`() {
        composeTestRule.setContent {
            LocalizedContent(AppLocale.GERMAN) {
                LocalizedAlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.redeem_title)) },
                    text = { Text(stringResource(R.string.redeem_code)) },
                    confirmButton = {
                        TextButton(onClick = {}) { Text(stringResource(R.string.action_join)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Liste beitreten").assertIsDisplayed()
        composeTestRule.onNodeWithText("Einladungscode oder Link").assertIsDisplayed()
        composeTestRule.onNodeWithText("Beitreten").assertIsDisplayed()
        composeTestRule.onNodeWithText("Abbrechen").assertIsDisplayed()
    }

    /**
     * The reason [LocalizedAlertDialog] has to exist, asserted rather than asserted-in-a-comment:
     * a plain Material3 AlertDialog under the very same provider still renders English.
     *
     * If this ever fails, Compose has started carrying the caller's context across the window
     * boundary and the wrapper can be reconsidered — so a failure here is information, not a
     * regression.
     */
    @Test
    fun `a plain AlertDialog does not, which is why the wrapper exists`() {
        composeTestRule.setContent {
            LocalizedContent(AppLocale.GERMAN) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.redeem_title)) },
                    confirmButton = {
                        TextButton(onClick = {}) { Text(stringResource(R.string.action_join)) }
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Join a list").assertIsDisplayed()
    }

    @Test
    fun `content outside a dialog was already localized and stays so`() {
        composeTestRule.setContent {
            LocalizedContent(AppLocale.GERMAN) { Text(stringResource(R.string.redeem_title)) }
        }

        composeTestRule.onNodeWithText("Liste beitreten").assertIsDisplayed()
    }
}
