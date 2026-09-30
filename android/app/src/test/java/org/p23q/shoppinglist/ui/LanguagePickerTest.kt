package org.p23q.shoppinglist.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AppLocale
import org.robolectric.RobolectricTestRunner

/** The language picker looks like one (C5): a read-only field labelled "Language" with a menu. */
@RunWith(RobolectricTestRunner::class)
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
}
