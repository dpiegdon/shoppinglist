package org.p23q.shoppinglist.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AppLocale
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The language picker looks like one (C5): a read-only field labelled "Language" with a menu. */
@RunWith(RobolectricTestRunner::class)
// Tall enough for all ten languages in the open menu.
@Config(qualifiers = "w411dp-h891dp")
class LanguagePickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a read-only field labelled Language shows the choice and opens the languages`() {
        var chosen: AppLocale? = null
        composeTestRule.setContent { LanguagePicker(selected = AppLocale.ENGLISH, onSelect = { chosen = it }) }

        // One field: the label is joined to the value, as the web's <select> with its <label>.
        composeTestRule.onNodeWithTag("language-picker")
            .assertTextContains("Language")
            .assertTextContains("English")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

        composeTestRule.onNodeWithTag("language-picker").performClick()
        composeTestRule.onNodeWithText("Deutsch").performClick()
        assertEquals(AppLocale.GERMAN, chosen)
    }

    @Test
    fun `Emoji is the last entry and says what it is in the current language (T-359)`() {
        var chosen: AppLocale? = null
        composeTestRule.setContent {
            LocalizedContent(AppLocale.JAPANESE) {
                LanguagePicker(selected = AppLocale.JAPANESE, onSelect = { chosen = it })
            }
        }

        composeTestRule.onNodeWithTag("language-picker").performClick()
        val entries = composeTestRule.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction())
        entries.onLast().assertTextEquals("😀🔤 (絵文字)")
        entries.onLast().performClick()
        assertEquals(AppLocale.EMOJI, chosen)
    }

    @Test
    fun `while the app shows Emoji, its entry is its own name alone`() {
        composeTestRule.setContent {
            LocalizedContent(AppLocale.EMOJI) {
                LanguagePicker(selected = AppLocale.EMOJI, onSelect = {})
            }
        }

        composeTestRule.onNodeWithTag("language-picker").assertTextContains("😀🔤")
        composeTestRule.onNodeWithTag("language-picker").performClick()
        composeTestRule.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).onLast().assertTextEquals("😀🔤")
    }

    @Test
    fun `in English the Emoji entry reads Emoji`() {
        composeTestRule.setContent { LanguagePicker(selected = AppLocale.ENGLISH, onSelect = {}) }

        composeTestRule.onNodeWithTag("language-picker").performClick()
        composeTestRule.onNodeWithText("😀🔤 (Emoji)").assertTextEquals("😀🔤 (Emoji)")
    }
}
