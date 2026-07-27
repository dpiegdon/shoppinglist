package org.p23q.shoppinglist.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.robolectric.RobolectricTestRunner

/** T-126: user-authored text embedded in a sentence must not reorder its surroundings. */
@RunWith(RobolectricTestRunner::class)
class UiTextBidiTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `an all-LTR value is passed through untouched`() {
        // The overwhelmingly common case must stay byte-for-byte unchanged: isolates are invisible
        // but they are real characters, and adding them unconditionally would alter every
        // interpolated string in the app to buy nothing here.
        val rendered = UiText.res(R.string.list_edit_item, "Milk").asString(context)

        assertEquals("Edit Milk", rendered)
    }

    @Test
    fun `an RTL value is isolated from the surrounding sentence`() {
        val rendered = UiText.res(R.string.list_edit_item, "حليب").asString(context)

        // BidiFormatter wraps with isolate/embedding marks; the exact marks are its business, but
        // the value must no longer sit bare against the English text around it.
        assertTrue(rendered, rendered.contains("حليب"))
        assertTrue("expected isolation marks in: $rendered", rendered != "Edit حليب")
    }

    @Test
    fun `a nested UiText argument still resolves`() {
        val rendered = UiText.res(
            R.string.sync_synced,
            UiText.res(R.string.ago_minutes, 5),
        ).asString(context)

        assertEquals("Synced 5 min ago", rendered)
    }

    @Test
    fun `a numeric argument is not treated as text needing isolation`() {
        assertEquals("Sessions: 3", UiText.res(R.string.admin_session_count, 3).asString(context))
    }
}
