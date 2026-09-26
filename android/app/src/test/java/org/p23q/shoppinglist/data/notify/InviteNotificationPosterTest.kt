package org.p23q.shoppinglist.data.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainActivity
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.InviteCheckOutcome
import org.p23q.shoppinglist.core.sync.PendingInvite
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.testAccount
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class InviteNotificationPosterTest {

    private lateinit var context: Context
    private lateinit var prefs: NotificationPrefsStore
    private lateinit var foreground: AppForegroundState
    private lateinit var poster: InviteNotificationPoster
    private lateinit var notificationManager: NotificationManager
    private lateinit var db: AppDb

    private val later = System.currentTimeMillis() + 86_400_000

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val file = File.createTempFile("invite_poster_prefs_test", ".preferences_pb").apply { deleteOnExit() }
        prefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { file })
        foreground = AppForegroundState()
        val localeFile = File.createTempFile("invite_poster_locale_test", ".preferences_pb").apply { deleteOnExit() }
        val locales = LocalePreferenceStore(PreferenceDataStoreFactory.create { localeFile })
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).setDriver(BundledSQLiteDriver()).build()
        runBlocking { db.insertTestAccount() }
        poster = InviteNotificationPoster(context, prefs, foreground, locales, AccountRegistry(db))
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun Notification.title(): String? = extras.getString(Notification.EXTRA_TITLE)
    private fun Notification.text(): String? = extras.getString(Notification.EXTRA_TEXT)
    private fun Notification.subText(): String? = extras.getString(Notification.EXTRA_SUB_TEXT)
    private fun posted() = shadowOf(notificationManager).allNotifications

    private fun invite(id: String, list: String, accountId: String = TEST_ACCOUNT_ID) =
        PendingInvite(accountId, id, list, invitedByInitials = "AB", expiresAt = later)

    private suspend fun recorded(): Pair<InviteCheckOutcome, Int>? =
        prefs.lastInviteCheck.first()?.let { it.outcome to it.newInvites }

    @Test
    fun `a new invite posts one notification naming the list and its sender, in its own channel`() = runTest {
        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))

        val n = posted().single()
        assertEquals("Groceries", n.title())
        assertEquals("From AB", n.text())
        // One account on the phone: nobody else it could be for.
        assertNull(n.subText())
        assertEquals(InviteNotificationPoster.CHANNEL_ID, n.channelId)
        assertEquals("Invitations", notificationManager.getNotificationChannel(InviteNotificationPoster.CHANNEL_ID).name)
        assertEquals(InviteCheckOutcome.POSTED to 1, recorded())
    }

    @Test
    fun `tapping it opens the overview`() = runTest {
        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))

        val tap = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, tap.component?.className)
        assertTrue(tap.getBooleanExtra(MainActivity.EXTRA_OPEN_OVERVIEW, false))
        assertNull(tap.getStringExtra(MainActivity.EXTRA_OPEN_LIST_ID))
    }

    @Test
    fun `an invite already seen on this phone is not posted again`() = runTest {
        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))
        notificationManager.cancelAll()

        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))

        assertTrue(posted().isEmpty())
        assertEquals(InviteCheckOutcome.NOTHING_NEW to 0, recorded())
    }

    @Test
    fun `only the unseen among the listed invites are announced, and a newer notification replaces the older`() = runTest {
        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))

        poster.notifyPendingInvites(listOf(invite("i1", "Groceries"), invite("i2", "Hardware")))

        val n = posted().single()
        assertEquals("Hardware", n.title())
        assertEquals(InviteCheckOutcome.POSTED to 1, recorded())
    }

    @Test
    fun `several new invites are one notification counting them and naming their lists and accounts`() = runTest {
        db.insertTestAccount(testAccount(id = "second", accountId = "acct-2", email = "work@example.com", serverUrl = "https://work.example.test/"))

        poster.notifyPendingInvites(listOf(invite("i1", "Groceries"), invite("i2", "Office", accountId = "second")))

        val n = posted().single()
        assertEquals("Invitations: 2", n.title())
        assertEquals("Groceries, Office", n.text())
        assertEquals("me@example.com · lists.example.test, work@example.com · work.example.test", n.subText())
        assertEquals(InviteCheckOutcome.POSTED to 2, recorded())
    }

    @Test
    fun `with the app in the foreground nothing is posted, and the invites count as seen`() = runTest {
        foreground.isForeground = true
        prefs.setInviteNotificationsEnabled(false)

        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))

        assertTrue(posted().isEmpty())
        // The first gate in order wins.
        assertEquals(InviteCheckOutcome.FOREGROUND to 1, recorded())

        foreground.isForeground = false
        prefs.setInviteNotificationsEnabled(true)
        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `the Invitations switch off stops it, and the collaborator switch does not`() = runTest {
        prefs.setInviteNotificationsEnabled(false)
        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))
        assertTrue(posted().isEmpty())
        assertEquals(InviteCheckOutcome.INVITES_OFF to 1, recorded())

        prefs.setInviteNotificationsEnabled(true)
        prefs.setNotificationsEnabled(false)
        poster.notifyPendingInvites(listOf(invite("i2", "Hardware")))
        assertEquals("Hardware", posted().single().title())
    }

    @Test
    fun `a missing permission stops it`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        poster.notifyPendingInvites(listOf(invite("i1", "Groceries")))

        assertTrue(posted().isEmpty())
        assertEquals(InviteCheckOutcome.NO_PERMISSION to 1, recorded())
    }

    @Test
    fun `an empty inbox is a check with nothing new`() = runTest {
        poster.notifyPendingInvites(emptyList())

        assertTrue(posted().isEmpty())
        assertEquals(InviteCheckOutcome.NOTHING_NEW to 0, recorded())
    }
}
