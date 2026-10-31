package org.p23q.shoppinglist.ui.listprops

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ListGuest
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Guests card of a ledger's properties (T-364). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class ListPropsGuestsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val server = MockWebServer()
    private val requests = mutableListOf<String>()
    private lateinit var db: AppDb
    private lateinit var listsRepo: ListsRepo
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listId: String
    private lateinit var viewModel: ListPropsViewModel

    private val me = "acct-me"
    private val dave = ListGuest("g:dave", "Dave", "DA")
    private val eve = ListGuest("g:eve", "Eve", "EV", claimedBy = "acct-me")

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
        server.shutdown()
    }

    private suspend fun show(
        protocol: Int = 4,
        kind: String = ListKind.EXPENSES,
        closeVotes: List<String> = emptyList(),
        closedAt: Long? = null,
        guests: List<ListGuest> = listOf(dave, eve),
        entries: List<Expense> = emptyList(),
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(requests) { if ("/guests" in path) requests += "${request.method} $path ${request.body.readUtf8()}".trim() }
                return when {
                    path.endsWith("/members") -> MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}""")
                    "/guests" in path && request.method == "DELETE" && !path.endsWith("/claim") -> MockResponse().setResponseCode(204)
                    "g:dave" in path -> MockResponse().setResponseCode(200)
                        .setBody("""{"id": "g:dave", "name": "Dave", "initials": "DA", "locked": false, "claimed_by": null, "created_at": 1}""")
                    "/guests" in path -> MockResponse().setResponseCode(200)
                        .setBody("""{"id": "g:new", "name": "Fay", "initials": "FA", "locked": false, "claimed_by": null, "created_at": 9}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error": "not_found", "message": "no"}""")
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount(serverUrl = server.url("/").toString()).copy(serverProtocol = protocol))
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip", kind, currency = if (ListKind.isExpenses(kind)) "EUR" else null)
        val members = listOf(ListMember(me, "me@example.com", "ME"))
        db.listDao().upsert(
            listsRepo.getById(listId)!!.copy(
                membersJson = Json.encodeToString(members),
                guestsJson = Json.encodeToString(guests),
                closeVotesJson = Json.encodeToString(closeVotes),
                closedAt = closedAt,
            ),
        )
        entries.forEach { itemsRepo.createExpense(listId, "Entry", it) }
        viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_guests_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()
    }

    private fun waitForRequests(count: Int) {
        repeat(50) {
            composeTestRule.waitForIdle()
            if (synchronized(requests) { requests.size } >= count && !viewModel.uiState.value.isGuestBusy) return
            Thread.sleep(100)
        }
    }

    private val serverId: String get() = runBlocking { listsRepo.getById(listId)!!.serverId }

    @Test
    fun `a ledger shows its guests, an unclaimed one with its controls and a claimed one with its claimant`() = runBlocking<Unit> {
        show()

        composeTestRule.onNodeWithTag("guests-card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Dave").assertIsDisplayed()
        composeTestRule.onNodeWithText("Amounts locked").assertIsDisplayed()
        composeTestRule.onNodeWithText("Locked: the amounts cannot change, like a member who agreed to close").assertIsDisplayed()
        composeTestRule.onNodeWithText("This is me").assertIsEnabled()
        composeTestRule.onNodeWithText("Remove").assertIsEnabled()
        // Eve is mine: no switch or claim for her, but Unclaim.
        composeTestRule.onNodeWithText("claimed by ME").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unclaim").assertIsEnabled()
        assertEquals(1, composeTestRule.onAllNodesWithText("This is me").fetchSemanticsNodes().size)
    }

    @Test
    fun `a guest a live entry names cannot be removed, and the card says why`() = runBlocking<Unit> {
        show(entries = listOf(Expense(mapOf(me to "10.00"), true, mapOf(dave.id to "10.00"), true, "2026-10-31")))

        composeTestRule.onNodeWithText("Remove").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Named in entries — edit those first").assertIsDisplayed()
    }

    @Test
    fun `the controls call the guest endpoints`() = runBlocking<Unit> {
        show()

        composeTestRule.onNodeWithText("This is me").performScrollTo().performClick()
        waitForRequests(1)
        composeTestRule.onNodeWithTag("guest-lock-${dave.id}").performScrollTo().performClick()
        waitForRequests(2)
        composeTestRule.onNodeWithTag("guest-add-field").performScrollTo().performTextInput("  Fay ")
        composeTestRule.onNodeWithText("Add").performClick()
        waitForRequests(3)

        assertEquals(
            listOf(
                "POST /api/v1/lists/$serverId/guests/g:dave/claim",
                "PATCH /api/v1/lists/$serverId/guests/g:dave {\"locked\":true}",
                "POST /api/v1/lists/$serverId/guests {\"name\":\"Fay\"}",
            ),
            synchronized(requests) { requests.toList() },
        )
    }

    @Test
    fun `a name the server would refuse is refused here, and nothing is sent`() = runBlocking<Unit> {
        show()

        composeTestRule.onNodeWithTag("guest-add-field").performScrollTo().performTextInput("x".repeat(65))
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Enter a guest name of up to 64 characters").assertIsDisplayed()
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `having agreed to close, every guest control is off`() = runBlocking<Unit> {
        show(closeVotes = listOf(me))

        composeTestRule.onNodeWithText("This is me").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Remove").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Unclaim").assertIsNotEnabled()
    }

    @Test
    fun `on a closed ledger the guests are read-only`() = runBlocking<Unit> {
        show(closeVotes = listOf(me), closedAt = 1_760_000_000_000)

        composeTestRule.onNodeWithText("Dave").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("This is me").assertDoesNotExist()
        composeTestRule.onNodeWithText("Remove").assertDoesNotExist()
        composeTestRule.onNodeWithText("Unclaim").assertDoesNotExist()
        composeTestRule.onNodeWithTag("guest-add-field").assertDoesNotExist()
    }

    @Test
    fun `against a protocol-3 server there is no Guests card`() = runBlocking<Unit> {
        show(protocol = 3)

        composeTestRule.onNodeWithTag("guests-card").assertDoesNotExist()
    }

    @Test
    fun `a shopping list has no Guests card`() = runBlocking<Unit> {
        show(kind = ListKind.SHOPPING, guests = emptyList())

        composeTestRule.onNodeWithTag("guests-card").assertDoesNotExist()
    }
}
