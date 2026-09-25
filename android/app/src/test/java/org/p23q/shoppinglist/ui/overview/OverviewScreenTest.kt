package org.p23q.shoppinglist.ui.overview

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The overview by account (T-292): one account is the old screen; several get sections. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OverviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private lateinit var listsRepo: ListsRepo
    private lateinit var itemsRepo: ItemsRepo
    private val viewModels = mutableListOf<OverviewViewModel>()

    @Before
    fun setUp() = runBlocking<Unit> {
        server = MockWebServer()
        // Every account's inbox is empty; the invites themselves are the view model test's.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"invites": []}""")
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        accounts = TestAccounts(db)
        val deviceId = DeviceIdProvider { "device-1" }
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        accounts.add(server.url("/").toString(), email = "me@example.com")
        listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, ::idleMainLooper, viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
        if (::server.isInitialized) server.shutdown()
    }

    private suspend fun addWorkAccount(token: String? = "tok-work") {
        accounts.add(server.url("/work/").toString(), id = "work", token = token, accountId = "acct-work", email = "me@work.example")
        listsRepo.create("work", "Office supplies")
    }

    private fun show(onSignIn: (String) -> Unit = {}) {
        val viewModel = OverviewViewModel(
            listsRepo,
            itemsRepo,
            accounts.registry,
            accounts.sessions,
            accounts.secrets,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
        ).also(viewModels::add)
        composeTestRule.setContent { OverviewScreen(onOpenList = {}, onSignIn = onSignIn, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.lists.isNotEmpty() && viewModel.uiState.value.accounts.isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `one account shows its lists with no account header and no marker`() {
        show()

        composeTestRule.onNodeWithText("Groceries").assertExists()
        composeTestRule.onAllNodesWithTag("account-header-$TEST_ACCOUNT_ID").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("me@example.com").assertCountEquals(0)
    }

    @Test
    fun `two accounts each get a header of email and server, and each card its account`() = runBlocking<Unit> {
        addWorkAccount()
        show()

        composeTestRule.onNodeWithTag("account-header-$TEST_ACCOUNT_ID").assertExists()
        composeTestRule.onNodeWithTag("account-header-work").assertExists()
        composeTestRule.onNodeWithText(server.url("/work/").toString()).assertExists()
        composeTestRule.onNodeWithText("Office supplies").assertExists()
        // Header and card marker, per account.
        composeTestRule.onAllNodesWithText("me@example.com").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("me@work.example").assertCountEquals(2)
    }

    @Test
    fun `a signed-out account's section offers sign-in for that account`() = runBlocking<Unit> {
        addWorkAccount(token = null)
        var signIn: String? = null
        show(onSignIn = { signIn = it })

        composeTestRule.onAllNodesWithText("Signed out. Tap to sign in.").assertCountEquals(1)
        // Its lists stay on the overview.
        composeTestRule.onNodeWithText("Office supplies").assertExists()
        composeTestRule.onNodeWithText("Signed out. Tap to sign in.").performClick()

        assertEquals("work", signIn)
    }

    @Test
    fun `a single signed-out account still gets the sign-in banner`() = runBlocking<Unit> {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(signedIn = false) }
        show()

        composeTestRule.onNodeWithText("Signed out. Tap to sign in.").assertExists()
        composeTestRule.onNodeWithText("Groceries").assertExists()
    }

    @Test
    fun `an outdated account's section says so and keeps its lists`() = runBlocking<Unit> {
        addWorkAccount()
        accounts.registry.update("work") { it.copy(outdated = true) }
        show()

        composeTestRule.onAllNodesWithText("This server needs a newer app.").assertCountEquals(1)
        composeTestRule.onNodeWithText("Office supplies").assertExists()
        composeTestRule.onAllNodesWithText("Signed out. Tap to sign in.").assertCountEquals(0)
    }

    @Test
    fun `the local area is the last section, "On this phone", its cards marked by the phone glyph (T-293)`() = runBlocking<Unit> {
        val local = accounts.registry.addLocal()!!
        listsRepo.create(local.id, "Hardware")
        addWorkAccount()
        show()

        composeTestRule.onNodeWithTag("account-header-${local.id}").assertExists()
        composeTestRule.onNodeWithText("On this phone").assertExists()
        composeTestRule.onNodeWithText(LOCAL_AREA_GLYPH).assertExists()
        val headers = listOf(TEST_ACCOUNT_ID, "work", local.id).map { id ->
            composeTestRule.onNodeWithTag("account-header-$id").fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("server accounts first, the local area last", headers.sorted(), headers)
    }

    @Test
    fun `with only the local area the overview is the plain one-account screen (T-293)`() = runBlocking<Unit> {
        accounts.registry.remove(TEST_ACCOUNT_ID)
        val local = accounts.registry.addLocal()!!
        listsRepo.create(local.id, "Hardware")
        show()

        composeTestRule.onNodeWithText("Hardware").assertExists()
        composeTestRule.onAllNodesWithTag("account-header-${local.id}").assertCountEquals(0)
        composeTestRule.onAllNodesWithText(LOCAL_AREA_GLYPH).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("On this phone").assertCountEquals(0)
    }
}

/** The New-list dialog's account picker (T-292), on its own: a dialog is its own window. */
@RunWith(RobolectricTestRunner::class)
class NewListDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun showDialog(state: OverviewUiState, onAccountChange: (String) -> Unit = {}) {
        composeTestRule.setContent {
            NewListDialog(
                state = state.copy(isCreateDialogOpen = true),
                onNameChange = {},
                onKindChange = {},
                onCurrencyChange = {},
                onAccountChange = onAccountChange,
                onCreate = {},
                onDismiss = {},
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the new-list dialog asks which account only with several`() {
        showDialog(OverviewUiState(accounts = listOf(testAccount()), newListAccountId = TEST_ACCOUNT_ID))

        composeTestRule.onNodeWithText("New list").assertExists()
        composeTestRule.onAllNodesWithText("Account").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("new-list-account-$TEST_ACCOUNT_ID").assertCountEquals(0)
    }

    @Test
    fun `with two accounts the new-list dialog offers both by email and server`() {
        val work = testAccount(id = "work", accountId = "acct-work", email = "me@work.example", serverUrl = "https://lists.example.test/work/")
        var chosen: String? = null
        showDialog(
            OverviewUiState(accounts = listOf(testAccount(), work), newListAccountId = TEST_ACCOUNT_ID),
            onAccountChange = { chosen = it },
        )

        composeTestRule.onNodeWithText("Account").assertExists()
        composeTestRule.onNodeWithTag("new-list-account-$TEST_ACCOUNT_ID").assertExists()
        composeTestRule.onNodeWithText("https://lists.example.test/work/").assertExists()
        composeTestRule.onNodeWithText("me@work.example").performClick()

        assertEquals("work", chosen)
    }

    @Test
    fun `the new-list dialog offers a ledger for a server account, not for the local area (T-293)`() {
        val local = org.p23q.shoppinglist.ui.accounts.localAccountRow()
        var state by androidx.compose.runtime.mutableStateOf(OverviewUiState(accounts = listOf(testAccount(), local), newListAccountId = TEST_ACCOUNT_ID))
        composeTestRule.setContent {
            NewListDialog(
                state = state.copy(isCreateDialogOpen = true),
                onNameChange = {},
                onKindChange = {},
                onCurrencyChange = {},
                onAccountChange = { id -> state = state.copy(newListAccountId = id) },
                onCreate = {},
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithTag("new-list-kind-expenses").assertExists()

        composeTestRule.onNodeWithTag("new-list-account-${local.id}").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("new-list-kind-expenses").assertDoesNotExist()
        composeTestRule.onNodeWithTag("new-list-kind-shopping").assertExists()
        composeTestRule.onNodeWithTag("new-list-kind-checklist").assertExists()
        // The local area by its name in the app's language.
        composeTestRule.onNodeWithText("On this phone").assertExists()
    }
}
