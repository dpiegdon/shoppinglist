package org.p23q.shoppinglist.data.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainActivity
import kotlinx.coroutines.flow.first
import org.p23q.shoppinglist.core.sync.ChangeCheckOutcome
import org.p23q.shoppinglist.core.sync.CollaboratorChange
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CollaboratorChangeNotificationPosterTest {

    private lateinit var context: Context
    private lateinit var prefs: NotificationPrefsStore
    private lateinit var foreground: AppForegroundState
    private lateinit var poster: CollaboratorChangeNotificationPoster
    private lateinit var notificationManager: NotificationManager
    private lateinit var db: AppDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // POST_NOTIFICATIONS is a runtime permission on API 33+; Robolectric doesn't auto-grant
        // it, so simulate the post-approval state the poster gates on (canPost()).
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val file = File.createTempFile("poster_prefs_test", ".preferences_pb")
        file.deleteOnExit()
        prefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { file })
        foreground = AppForegroundState()
        val localeFile = File.createTempFile("poster_locale_test", ".preferences_pb")
        localeFile.deleteOnExit()
        // No stored choice, so it follows the device — which under Robolectric is English, i.e.
        // exactly the strings these assertions expect.
        val locales = LocalePreferenceStore(PreferenceDataStoreFactory.create { localeFile })
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).setDriver(BundledSQLiteDriver()).build()
        runBlocking { db.insertTestAccount() }
        poster = CollaboratorChangeNotificationPoster(context, prefs, foreground, locales, AccountRegistry(db))
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    private fun Notification.title(): String? = extras.getString(Notification.EXTRA_TITLE)
    private fun Notification.text(): String? = extras.getString(Notification.EXTRA_TEXT)
    private fun Notification.subText(): String? = extras.getString(Notification.EXTRA_SUB_TEXT)

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `with one account the notification names no account (T-292)`() = runTest {
        poster.notifyCollaboratorChanges(listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 3)))

        assertNull(shadowOf(notificationManager).allNotifications.single().subText())
    }

    @Test
    fun `with several accounts the notification names the account whose list changed (T-292)`() = runTest {
        db.insertTestAccount(testAccount(id = "second", accountId = "acct-2", email = "work@example.com", serverUrl = "https://work.example.test/"))
        // Before the poster's registry first reads the table, as a second sign-in would be.

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange("second", "list-9", "Office", 1)))

        val n = shadowOf(notificationManager).allNotifications.single()
        assertEquals("Office", n.title())
        // Email and server (T-300): the same email may be signed in on two instances.
        assertEquals("work@example.com · work.example.test", n.subText())
    }

    @Test
    fun `a single changed list posts one notification naming the list, tapping opens it`() = runTest {
        poster.notifyCollaboratorChanges(listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 3)))

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        val n = posted.single()
        assertEquals("Groceries", n.title())
        assertEquals("Changed items: 3", n.text())
        val tapIntent = shadowOf(n.contentIntent).savedIntent
        assertEquals("list-1", tapIntent.getStringExtra(MainActivity.EXTRA_OPEN_LIST_ID))
    }

    @Test
    fun `multiple changed lists collapse to one summary notification opening the overview`() = runTest {
        poster.notifyCollaboratorChanges(
            listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 2), CollaboratorChange(TEST_ACCOUNT_ID, "list-2", "Hardware", 1)),
        )

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        val n = posted.single()
        assertEquals("Changed items: 3 · Lists: 2", n.text())
        assertNull(shadowOf(n.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_OPEN_LIST_ID))
    }

    @Test
    fun `the global toggle off suppresses everything`() = runTest {
        prefs.setNotificationsEnabled(false)

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 3)))

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a muted list is dropped while other lists still notify`() = runTest {
        prefs.setListMuted("list-1", muted = true)

        poster.notifyCollaboratorChanges(
            listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 2), CollaboratorChange(TEST_ACCOUNT_ID, "list-2", "Hardware", 1)),
        )

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        assertEquals("Hardware", posted.single().title())
    }

    @Test
    fun `every audible list muted means nothing is posted`() = runTest {
        prefs.setListMuted("list-1", muted = true)

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 2)))

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a foregrounded app suppresses notifications — the change is already on screen`() = runTest {
        foreground.isForeground = true

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 2)))

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `singular item count reads naturally`() = runTest {
        poster.notifyCollaboratorChanges(listOf(CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 1)))

        assertEquals("Changed items: 1", shadowOf(notificationManager).allNotifications.single().text())
    }

    // ---- the diagnostics line's record (T-318) -----------------------------------------------

    private suspend fun recorded(): Pair<ChangeCheckOutcome, Int>? =
        prefs.lastChangeCheck.first()?.let { it.outcome to it.foreignItems }

    private val twoLists = listOf(
        CollaboratorChange(TEST_ACCOUNT_ID, "list-1", "Groceries", 2),
        CollaboratorChange(TEST_ACCOUNT_ID, "list-2", "Hardware", 1),
    )

    @Test
    fun `a posted notification is recorded with every foreign item, muted lists included (T-318)`() = runTest {
        prefs.setListMuted("list-1", muted = true)

        poster.notifyCollaboratorChanges(twoLists)

        assertEquals(ChangeCheckOutcome.POSTED to 3, recorded())
        assertTrue(prefs.lastChangeCheck.first()!!.atMillis > 0)
    }

    @Test
    fun `a foregrounded app is recorded as the gate that stopped it (T-318)`() = runTest {
        foreground.isForeground = true
        prefs.setNotificationsEnabled(false)

        poster.notifyCollaboratorChanges(twoLists)

        // The first gate in order wins.
        assertEquals(ChangeCheckOutcome.FOREGROUND to 3, recorded())
    }

    @Test
    fun `the global toggle off is recorded (T-318)`() = runTest {
        prefs.setNotificationsEnabled(false)
        prefs.setListMuted("list-1", muted = true)
        prefs.setListMuted("list-2", muted = true)

        poster.notifyCollaboratorChanges(twoLists)

        assertEquals(ChangeCheckOutcome.NOTIFICATIONS_OFF to 3, recorded())
    }

    @Test
    fun `every list muted is recorded (T-318)`() = runTest {
        prefs.setListMuted("list-1", muted = true)
        prefs.setListMuted("list-2", muted = true)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        poster.notifyCollaboratorChanges(twoLists)

        assertEquals(ChangeCheckOutcome.LIST_MUTED to 3, recorded())
    }

    @Test
    fun `a missing permission is recorded (T-318)`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        poster.notifyCollaboratorChanges(twoLists)

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertEquals(ChangeCheckOutcome.NO_PERMISSION to 3, recorded())
    }

    @Test
    fun `system notifications off for the app are recorded as no permission (T-318)`() = runTest {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        poster.notifyCollaboratorChanges(twoLists)

        assertEquals(ChangeCheckOutcome.NO_PERMISSION to 3, recorded())
    }

    @Test
    fun `a check the engine ended is recorded as it says (T-318)`() = runTest {
        poster.recordCheck(ChangeCheckOutcome.FIRST_SYNC, foreignItems = 0)

        assertEquals(ChangeCheckOutcome.FIRST_SYNC to 0, recorded())
    }
}
