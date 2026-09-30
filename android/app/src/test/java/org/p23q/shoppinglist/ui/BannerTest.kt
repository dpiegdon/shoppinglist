package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.sync.SyncState
import org.robolectric.RobolectricTestRunner

/** Every banner is one shape (A9): inset from the screen's edges, the same padding inside. */
@RunWith(RobolectricTestRunner::class)
class BannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the attention banner is inset from the screen's edges, not a full-bleed strip`() {
        composeTestRule.setContent {
            InBrandColors {
                Box(Modifier.width(300.dp)) {
                    SyncStatusBar(state = SyncState(blockedCount = 2), nowMs = 0L, onAttentionClick = {}, showRecency = false)
                }
            }
        }

        val bounds = composeTestRule.onNodeWithTag(ATTENTION_BANNER_TAG).getBoundsInRoot()
        assertEquals(16.dp, bounds.left)
        assertEquals(284.dp, bounds.right)
    }

    @Test
    fun `the blocked-row banner pads its text as every banner does`() {
        composeTestRule.setContent {
            InBrandColors {
                Box(Modifier.width(400.dp).testTag("host")) { BlockedBanner(code = null, who = null) }
            }
        }

        val text = composeTestRule.onNodeWithText("Not saved to the list", substring = true, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(12.dp, text.left)
        assertEquals(8.dp, text.top)
    }
}
