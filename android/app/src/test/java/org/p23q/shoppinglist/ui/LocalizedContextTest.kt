package org.p23q.shoppinglist.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppLocale
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * T-128 regression: the localized context must stay unwrappable to the Activity.
 *
 * This existed as createConfigurationContext(), which returns a bare ContextImpl — NOT a
 * ContextWrapper. Anything that walks up the context chain looking for an Activity therefore
 * dead-ends at it. hiltViewModel() does exactly that walk, and because LocalizedContent overrides
 * LocalContext for the entire app, every screen's `viewModel = hiltViewModel()` default threw on
 * the first frame and the app could not start at all.
 *
 * Nothing caught it: the Compose tests render each screen directly against a fake ViewModel, so
 * they never go through MainActivity -> LocalizedContent -> hiltViewModel(). This test closes that
 * specific hole rather than the general one — it asserts the property hiltViewModel() depends on.
 */
@RunWith(RobolectricTestRunner::class)
class LocalizedContextTest {

    /** The walk HiltViewModelFactory performs. */
    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    @Test
    fun `the localized context still unwraps to the Activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val localized = localizedContext(activity, AppLocale.GERMAN)

        assertNotNull(
            "hiltViewModel() walks the context chain for an Activity; if this is null the app " +
                "crashes on its first frame",
            findActivity(localized),
        )
        assertEquals(activity, findActivity(localized))
    }

    @Test
    fun `the localized context actually resolves strings in that language`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val german = localizedContext(activity, AppLocale.GERMAN)
        val arabic = localizedContext(activity, AppLocale.ARABIC)

        // Keeping the Activity reachable must not cost the localization it exists to provide.
        assertEquals("Abbrechen", german.getString(R.string.action_cancel))
        assertEquals("إلغاء", arabic.getString(R.string.action_cancel))
    }

    @Test
    fun `an application context is still accepted, for code outside composition`() {
        // The notification poster resolves against the application context — no Activity to find,
        // and that is fine; it must simply not throw.
        val app = ApplicationProvider.getApplicationContext<Context>()

        val localized = localizedContext(app, AppLocale.GERMAN)

        assertEquals("Abbrechen", localized.getString(R.string.action_cancel))
        assertTrue(findActivity(localized) == null)
    }
}
