package org.p23q.shoppinglist.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.api.InviteForMeDto
import org.p23q.shoppinglist.ui.InBrandColors
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An invite on the overview keeps its buttons beside the name while the name keeps the web's 12rem,
 * and wraps them under it, at the end, when it would not (T-343), as the web's card wraps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class InviteCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(width: Dp) {
        val invite = InviteForMeDto(
            id = "i1",
            listId = "l1",
            listName = "Everything we still need for the summer house",
            listKind = "shopping",
            invitedByInitials = "AL",
            expiresAt = 5L * 24 * 3600 * 1000,
            token = "t",
        )
        composeTestRule.setContent {
            InBrandColors {
                Box(Modifier.width(width)) {
                    InviteCard(invite, nowMs = 0L, ignored = false, busy = false, onJoin = {}, onIgnore = {})
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `with room for the name, the buttons sit beside it`() {
        show(400.dp)
        val name = composeTestRule.onNodeWithTag("invite-text-i1").getBoundsInRoot()
        val buttons = composeTestRule.onNodeWithTag("invite-buttons-i1").getBoundsInRoot()
        assertTrue("buttons beside the name: $name / $buttons", buttons.top < name.bottom && buttons.left >= name.right)
    }

    @Test
    fun `on a narrow screen the buttons wrap under the name, at the end`() {
        show(280.dp)
        val name = composeTestRule.onNodeWithTag("invite-text-i1").getBoundsInRoot()
        val buttons = composeTestRule.onNodeWithTag("invite-buttons-i1").getBoundsInRoot()
        assertTrue("buttons under the name: $name / $buttons", buttons.top >= name.bottom)
        assertEquals("the name starts at the card's padding", 16f, name.left.value, 0.5f)
        // The card's 8dp end padding.
        assertEquals(272f, buttons.right.value, 0.5f)
    }
}
