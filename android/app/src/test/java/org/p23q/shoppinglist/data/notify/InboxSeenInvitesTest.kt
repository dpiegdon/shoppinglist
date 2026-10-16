package org.p23q.shoppinglist.data.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.InviteCheckOutcome
import org.p23q.shoppinglist.core.sync.PendingInvite
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.runCurrentOn
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.overview.OverviewViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The overview's inbox (in :shared) and the invite notifications (here) share what the phone has
 * seen: an invite rendered in the inbox is not announced again (T-322).
 */
@RunWith(RobolectricTestRunner::class)
class InboxSeenInvitesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private lateinit var server: MockWebServer
    private lateinit var notificationPrefs: NotificationPrefsStore
    private val viewModels = mutableListOf<OverviewViewModel>()

    private var inboxJson = """{"invites": []}"""

    private fun inviteJson(id: String, listName: String) =
        """{"id": "$id", "list_id": "list-$id", "list_name": "$listName", "list_kind": "shopping",
           "invited_by_initials": "AL", "expires_at": ${System.currentTimeMillis() + 5 * 86_400_000L}, "token": "token-$id"}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/invites/pending" -> MockResponse().setBody(inboxJson)
                else -> MockResponse().setResponseCode(404).setBody("""{"error": "not_found"}""")
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        accounts = TestAccounts(db)
        runTest(mainDispatcherRule.dispatcher) { accounts.add(server.url("/").toString()) }
        val prefsFile = File.createTempFile("inbox_seen_notif", ".preferences_pb").apply { deleteOnExit() }
        notificationPrefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile })
    }

    @After
    fun tearDown() {
        // Before the rule resets Dispatchers.Main (a rule's finished() runs after @After).
        if (::db.isInitialized) closeWhenIdle(db, runCurrentOn(mainDispatcherRule.dispatcher), viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
        viewModels.clear()
        if (::server.isInitialized) server.shutdown()
    }

    private fun newViewModel(): OverviewViewModel {
        val deviceId = DeviceIdProvider { "device-1" }
        val syncer = object : Syncer {
            override suspend fun syncNow(fullLists: List<String>): SyncResult = SyncResult.Success(0, 0, 0, 0)

            override suspend fun syncJoined(accountId: String, serverListId: String): SyncResult = syncNow(listOf(serverListId))
        }
        return OverviewViewModel(
            ListsRepo(db, deviceId, FakeSyncTrigger()),
            ItemsRepo(db, deviceId, FakeSyncTrigger()),
            accounts.registry,
            accounts.sessions,
            accounts.secrets,
            syncer,
            SyncStatus(),
            notificationPrefs,
        ).also(viewModels::add)
    }

    @Test
    fun `invites rendered in the inbox count as seen, so the next check stays quiet (T-322)`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}, ${inviteJson("b", "Chores")}]}"""
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(ignoredInviteIdsJson = AccountRegistry.encodeIds(setOf("b"))) }
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val localeFile = File.createTempFile("inbox_seen_locale", ".preferences_pb").apply { deleteOnExit() }
        val poster = InviteNotificationPoster(
            context,
            notificationPrefs,
            AppForegroundState(),
            LocalePreferenceStore(PreferenceDataStoreFactory.create { localeFile }),
            accounts.registry,
        )

        newViewModel().uiState.first { it.invites.isNotEmpty() }
        // The ignored one is shown too, greyed.
        notificationPrefs.seenInvites.first { it.keys == setOf("a", "b") }

        poster.notifyPendingInvites(
            listOf("a", "b").map { PendingInvite(TEST_ACCOUNT_ID, it, "List $it", "AL", System.currentTimeMillis() + 86_400_000L) },
        )
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertEquals(InviteCheckOutcome.NOTHING_NEW, notificationPrefs.lastInviteCheck.first()?.outcome)
    }
}
