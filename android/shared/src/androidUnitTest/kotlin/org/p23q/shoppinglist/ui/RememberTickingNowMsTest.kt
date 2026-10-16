package org.p23q.shoppinglist.ui

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [rememberTickingNowMs] (T-54): a recency label's "now" must advance without a state change. */
@RunWith(RobolectricTestRunner::class)
class RememberTickingNowMsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `does not tick before intervalMs has elapsed`() {
        composeTestRule.mainClock.autoAdvance = false
        var clockCalls = 0L
        var observed = -1L
        composeTestRule.setContent {
            observed = rememberTickingNowMs(intervalMs = 1_000L, clock = { clockCalls++ })
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        val initial = observed

        composeTestRule.mainClock.advanceTimeBy(500L)

        assertEquals(initial, observed)
    }

    @Test
    fun `ticks to a fresh clock reading once intervalMs has elapsed`() {
        composeTestRule.mainClock.autoAdvance = false
        var clockCalls = 0L
        var observed = -1L
        composeTestRule.setContent {
            observed = rememberTickingNowMs(intervalMs = 1_000L, clock = { clockCalls++ })
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        val initial = observed

        composeTestRule.mainClock.advanceTimeBy(1_500L)

        assertEquals(initial + 1, observed)
    }
}
