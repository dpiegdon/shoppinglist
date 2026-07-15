package org.p23q.shoppinglist.data.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NotificationPrefsStoreTest {

    private lateinit var store: NotificationPrefsStore

    @Before
    fun setUp() {
        val file = File.createTempFile("notification_prefs_test", ".preferences_pb")
        file.deleteOnExit()
        store = NotificationPrefsStore(PreferenceDataStoreFactory.create { file })
    }

    @Test
    fun `notifications are enabled by default with no lists muted`() = runTest {
        assertTrue(store.notificationsEnabled.first())
        assertTrue(store.mutedListIds.first().isEmpty())
    }

    @Test
    fun `setNotificationsEnabled false persists and can be re-enabled`() = runTest {
        store.setNotificationsEnabled(false)
        assertFalse(store.notificationsEnabled.first())

        store.setNotificationsEnabled(true)
        assertTrue(store.notificationsEnabled.first())
    }

    @Test
    fun `the notification-permission-requested flag defaults false and persists once set (T-72)`() = runTest {
        assertFalse(store.notificationPermissionRequested.first())

        store.setNotificationPermissionRequested(true)

        assertTrue(store.notificationPermissionRequested.first())
    }

    @Test
    fun `muting and unmuting a list only affects that list`() = runTest {
        store.setListMuted("list-a", muted = true)
        store.setListMuted("list-b", muted = true)
        assertEquals(setOf("list-a", "list-b"), store.mutedListIds.first())

        store.setListMuted("list-a", muted = false)
        assertEquals(setOf("list-b"), store.mutedListIds.first())
    }
}
