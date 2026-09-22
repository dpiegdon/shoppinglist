package org.p23q.shoppinglist.ui.expense

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.Expense
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.ListMember
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.sync.SyncResult
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Closing an expenses list, as the list screen shows it (T-158): the banner and the archive.
 *
 * Screen tests only. A Compose test rule drives the Main dispatcher itself, so pairing it with
 * MainDispatcherRule makes the two wait on each other and the class never finishes — the
 * view-model half of this feature lives in ExpenseFormViewModelTest, which has that rule instead.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseClosingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var listId: String
    private lateinit var dinnerId: String

    private val me = "acct-me"
    private val other = "acct-other"

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.createList("Trip", ListKind.EXPENSES, currency = "EUR")
        setListState()
        dinnerId = itemsRepo.createExpense(
            listId,
            "Dinner",
            Expense(mapOf(me to "60.00"), true, mapOf(me to "30.00", other to "30.00"), true, "2026-09-17"),
        )
    }

    /** The roster and vote state normally arrive from the server on the list row. */
    private suspend fun setListState(closeVotes: List<String> = emptyList(), closedAt: Long? = null) {
        val members = listOf(me, other).map {
            ListMember(it, "$it@example.com", it.substringAfter('-').take(2).uppercase())
        }
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(
            list.copy(
                membersJson = Json.encodeToString(members),
                closeVotesJson = Json.encodeToString(closeVotes),
                closedAt = closedAt,
            ),
        )
    }

    /**
     * A provider pointed at nothing: these tests cover what the screen shows, never the vote
     * request itself, so an API call here would be a test that lied about what it exercised.
     */
    private fun offlineApiProvider(): ApiProvider {
        val file = File.createTempFile("expense_closing_server_config", ".preferences_pb")
        file.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { file })
        val json = Json { ignoreUnknownKeys = true }
        return ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { null }),
            errorInterceptor = ErrorInterceptor(json, SessionEvents()),
            json = json,
        )
    }

    private fun listViewModel(apiProvider: ApiProvider = offlineApiProvider()) = ExpenseListViewModel(
        SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
        itemsRepo,
        listsRepo,
        apiProvider,
        Syncer { SyncResult.Success(0, 0, 0, 0) },
        FakeSessionState().apply { accountId = me },
    )

    /** A real (mock) server backing, for the one test below that exercises the vote request itself. */
    private fun apiProviderFor(server: MockWebServer): ApiProvider {
        val file = File.createTempFile("expense_closing_vote_server_config", ".preferences_pb")
        file.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { file })
        runBlocking { serverConfig.setServerUrl(server.url("/").toString()) }
        val json = Json { ignoreUnknownKeys = true }
        return ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, SessionEvents()),
            json = json,
        )
    }

    private fun formViewModel() =
        ExpenseFormViewModel(itemsRepo, listsRepo, FakeSessionState().apply { accountId = me })

    private fun showList(onEditExpense: (String) -> Unit = {}) {
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = onEditExpense,
                onOpenListProps = {},
                viewModel = listViewModel(),
            )
        }
        composeTestRule.waitForIdle()
    }

    // ---- the banner -----------------------------------------------------------

    @Test
    fun `says nothing while nobody has voted`() = runBlocking<Unit> {
        showList()

        composeTestRule.onNodeWithText("Agree to close").assertDoesNotExist()
        composeTestRule.onNodeWithText("Withdraw").assertDoesNotExist()
    }

    @Test
    fun `counts the votes once one is cast, and offers to agree`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(other))
        showList()

        composeTestRule.onNodeWithText("Votes to close: 1 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Agree to close").assertIsDisplayed()
    }

    @Test
    fun `offers to withdraw once I have voted`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(me))
        showList()

        composeTestRule.onNodeWithText("Withdraw").assertIsDisplayed()
        // Agreeing to close means being done: no Add for a voter (T-192).
        composeTestRule.onNodeWithContentDescription("Add entry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Agree to close").assertDoesNotExist()
    }

    @Test
    fun `once I have voted the expenses stay to read but open nothing (T-193)`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(me))
        var opened: String? = null
        showList(onEditExpense = { opened = it })

        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(null, opened)
    }

    @Test
    fun `before I vote an expense opens as usual`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(other))
        var opened: String? = null
        showList(onEditExpense = { opened = it })

        composeTestRule.onNodeWithText("Dinner").performClick()
        composeTestRule.waitForIdle()

        assertEquals(dinnerId, opened)
    }

    // ---- a write the server refused (T-200) ------------------------------------

    /** What SyncEngine leaves on a row the server refused: parked, with the reason on it. */
    private suspend fun park(code: String?, accountId: String? = null) =
        db.itemDao().blockRow(dinnerId, code, accountId)

    @Test
    fun `a refused expense says on the list why it was not saved, naming the person`() = runBlocking<Unit> {
        // The race the form cannot pre-empt: other voted while this edit sat in the push queue.
        park("participant_frozen", other)
        showList()

        composeTestRule.onNodeWithText("The amounts of OT are fixed", substring = true).assertIsDisplayed()
        // The mark is on the row, so it is also what TalkBack hears there.
        composeTestRule.onNodeWithContentDescription("Not saved to the list").assertExists()
    }

    @Test
    fun `the form opens with the reason at the top, in its own labelling`() = runBlocking<Unit> {
        park("participant_frozen", other)
        showDinner()

        composeTestRule.onNodeWithText("Not saved to the list").assertIsDisplayed()
        // The form names people by email, as its share rows do, not by the list's initials.
        composeTestRule
            .onNodeWithText("The amounts of $other@example.com are fixed", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a refusal this build cannot explain still marks the row`() = runBlocking<Unit> {
        // A code from a newer server, or a row parked before the reason was stored at all.
        park(code = null)
        showList()

        composeTestRule.onNodeWithText("Not saved to the list").assertIsDisplayed()
    }

    @Test
    fun `an expense the server never refused carries no mark`() = runBlocking<Unit> {
        showList()

        composeTestRule.onNodeWithText("Not saved to the list").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Not saved to the list").assertDoesNotExist()
    }

    // ---- deleting (T-193) -----------------------------------------------------

    private fun showDinner() {
        composeTestRule.setContent {
            ExpenseDialog(listId = listId, itemId = dinnerId, onDismiss = {}, viewModel = formViewModel())
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `an expense involving a voter cannot be deleted, and the form says why`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(other))
        showDinner()

        composeTestRule.onNodeWithText("Delete").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("This entry can't be deleted", substring = true).assertExists()
    }

    @Test
    fun `an expense nobody on it has voted about deletes as usual`() = runBlocking<Unit> {
        showDinner()

        composeTestRule.onNodeWithText("Delete").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithText("This entry can't be deleted", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a closed list says so and offers nothing to add`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(me, other), closedAt = 1_758_000_000_000)
        showList()

        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
        // By description: the Add button is an icon, so looking for it as text passed whether or not
        // it was there (T-168 found this).
        composeTestRule.onNodeWithContentDescription("Add entry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Votes to close: 1 of 2").assertDoesNotExist()
    }

    // ---- a vote request the server refused (T-264) -----------------------------

    @Test
    fun `agreeing to close shows the server's reason, not offline, when it refuses`() = runBlocking<Unit> {
        // The scenario T-264 named: a collaborator's vote closes the list while this screen is
        // open, and pressing "Agree to close" hits the server's 409 rather than the network.
        // ApiException extends IOException, so a catch-order mistake here reported it as offline.
        setListState(closeVotes = listOf(other))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(409).setBody("""{"error": "list_closed", "message": "closed"}""")
        }
        server.start()
        try {
            composeTestRule.setContent {
                ExpenseListScreen(
                    onAddExpense = {},
                    onEditExpense = {},
                    onOpenListProps = {},
                    viewModel = listViewModel(apiProviderFor(server)),
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Agree to close").performClick()
            // The vote is a real HTTP round trip via MockWebServer; a single waitForIdle() doesn't
            // reliably span the background IO completing, as RedeemDialogTest found before this.
            var attempts = 0
            while (attempts < 50) {
                composeTestRule.waitForIdle()
                if (composeTestRule.onAllNodesWithText("Could not record your vote.").fetchSemanticsNodes().isEmpty() &&
                    composeTestRule.onAllNodesWithText("This list has been closed and can no longer be changed.")
                        .fetchSemanticsNodes().isNotEmpty()
                ) {
                    break
                }
                Thread.sleep(100)
                attempts++
            }

            composeTestRule.onNodeWithText("This list has been closed and can no longer be changed.").assertIsDisplayed()
            composeTestRule.onNodeWithText("Could not record your vote.").assertDoesNotExist()
        } finally {
            server.shutdown()
        }
    }
}
