package org.p23q.shoppinglist.ui.redeem

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainActivity
import org.robolectric.RobolectricTestRunner
import android.content.Context

/**
 * The manifest's intent filter hands a tapped invite link to the app at every depth the nav graph's
 * deep links cover (T-300): the host's root and a mount path of one, two or three segments. Resolved
 * through the package manager, which reads the manifest, rather than the nav graph alone: a link the
 * system does not give the app never reaches [androidx.navigation.NavController.handleDeepLink].
 */
@RunWith(RobolectricTestRunner::class)
class InviteIntentFilterTest {

    private fun handlers(link: String): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
    }

    @Test
    fun `a link at the host's root opens the app`() {
        assertEquals(listOf(MainActivity::class.java.name), handlers("https://p23q.org/invite/abc.def"))
    }

    @Test
    fun `a link under a one-segment mount path opens the app`() {
        assertEquals(listOf(MainActivity::class.java.name), handlers("https://p23q.org/shopping/invite/abc.def"))
    }

    @Test
    fun `a link under a two-segment mount path opens the app`() {
        assertEquals(listOf(MainActivity::class.java.name), handlers("https://p23q.org/shopping/stage/invite/abc.def"))
    }

    @Test
    fun `a link under a three-segment mount path opens the app`() {
        assertEquals(listOf(MainActivity::class.java.name), handlers("https://p23q.org/a/b/c/invite/abc.def"))
    }

    @Test
    fun `a link that is no invite does not`() {
        assertEquals(emptyList<String>(), handlers("https://p23q.org/shopping/lists/abc.def"))
    }
}
