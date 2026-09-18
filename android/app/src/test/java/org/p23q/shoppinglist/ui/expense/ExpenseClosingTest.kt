package org.p23q.shoppinglist.ui.expense

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    private val me = "acct-me"
    private val other = "acct-other"

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        listId = listsRepo.createList("Trip", ListKind.EXPENSES, currency = "EUR")
        setListState()
        itemsRepo.createExpense(
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

    private fun listViewModel() = ExpenseListViewModel(
        SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
        itemsRepo,
        listsRepo,
        offlineApiProvider(),
        Syncer { SyncResult.Success(0, 0, 0, 0) },
        FakeSessionState().apply { accountId = me },
    )

    private fun formViewModel() =
        ExpenseFormViewModel(itemsRepo, listsRepo, FakeSessionState().apply { accountId = me })

    private fun showList() {
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
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
        composeTestRule.onNodeWithContentDescription("Add expense").assertDoesNotExist()
        composeTestRule.onNodeWithText("Agree to close").assertDoesNotExist()
    }

    @Test
    fun `a closed list says so and offers nothing to add`() = runBlocking<Unit> {
        setListState(closeVotes = listOf(me, other), closedAt = 1_758_000_000_000)
        showList()

        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
        // By description: the Add button is an icon, so looking for it as text passed whether or not
        // it was there (T-168 found this).
        composeTestRule.onNodeWithContentDescription("Add expense").assertDoesNotExist()
        composeTestRule.onNodeWithText("Votes to close: 1 of 2").assertDoesNotExist()
    }
}
