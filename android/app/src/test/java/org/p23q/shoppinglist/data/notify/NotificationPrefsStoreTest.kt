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

    @Test
    fun `invite notifications are on by default, apart from the collaborator switch (T-319)`() = runTest {
        assertTrue(store.inviteNotificationsEnabled.first())
        store.setInviteNotificationsEnabled(false)
        assertFalse(store.inviteNotificationsEnabled.first())
        assertTrue(store.notificationsEnabled.first())
        store.setNotificationsEnabled(false)
        store.setInviteNotificationsEnabled(true)
        assertTrue(store.inviteNotificationsEnabled.first())
    }

    @Test
    fun `an invite is new only the first time it is listed (T-319)`() = runTest {
        assertEquals(setOf("i1", "i2"), store.markInvitesSeen(mapOf("i1" to 5_000L, "i2" to 6_000L), now = 1_000))
        assertEquals(setOf("i3"), store.markInvitesSeen(mapOf("i1" to 5_000L, "i3" to 7_000L), now = 2_000))
        assertEquals(emptySet<String>(), store.markInvitesSeen(mapOf("i2" to 6_000L), now = 3_000))
    }

    @Test
    fun `a seen invite is forgotten once it has expired, not merely when it is absent (T-319)`() = runTest {
        store.markInvitesSeen(mapOf("i1" to 5_000L, "i2" to 9_000L), now = 1_000)

        // i2 is absent from this answer (its account's request may have failed): still remembered.
        store.markInvitesSeen(emptyMap(), now = 6_000)

        assertEquals(mapOf("i2" to 9_000L), store.seenInvites.first())
    }
}
