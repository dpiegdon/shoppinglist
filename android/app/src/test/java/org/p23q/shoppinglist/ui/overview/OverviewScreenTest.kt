package org.p23q.shoppinglist.ui.overview

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.p23q.shoppinglist.core.db.AccountEntity
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

    private fun show(onSignIn: (String) -> Unit = {}, onCheckForUpdate: () -> Unit = {}) {
        val viewModel = OverviewViewModel(
            listsRepo,
            itemsRepo,
            accounts.registry,
            accounts.sessions,
            accounts.secrets,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
        ).also(viewModels::add)
        composeTestRule.setContent {
            OverviewScreen(onOpenList = {}, onSignIn = onSignIn, onCheckForUpdate = onCheckForUpdate, viewModel = viewModel)
        }
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
        // The heading names the account once; the cards do not repeat it.
        composeTestRule.onAllNodesWithText("me@example.com").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("me@work.example").assertCountEquals(1)
    }

    @Test
    fun `an account header is one slim line, email then server, when it fits (T-307)`() = runBlocking<Unit> {
        addWorkAccount()
        show()

        val header = composeTestRule.onNodeWithTag("account-header-work").fetchSemanticsNode().boundsInRoot
        val email = composeTestRule.onNodeWithTag("account-header-email-work", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val url = composeTestRule.onNodeWithTag("account-header-server-work", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("server beside the email", url.left >= email.right)
        assertTrue("on the email's line", url.top < email.bottom && url.bottom > email.top)
        // Slim: the header's own box, below its small top gap, is one line tall and has no bottom padding.
        assertEquals(email.bottom, header.bottom, 1f)
        assertTrue("a small gap above, not the old 20dp", email.top - header.top < with(composeTestRule.density) { 16.dp.toPx() })
    }

    @Test
    fun `an invitations heading is slim and at the end of its line, where an account header starts at the start (T-309)`() {
        composeTestRule.setContent {
            Box(Modifier.width(400.dp)) { SectionHeading("Invitations", tag = "invites-heading-x") }
        }
        fun bounds(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val box = bounds("invites-heading-x")
        val text = bounds("invites-heading-x-text")

        assertEquals("at the end of the line", box.right, text.right, 1f)
        assertTrue("not at the start", text.left > box.left)
        assertEquals("nothing below", box.bottom, text.bottom, 1f)
        assertEquals("12dp above", with(composeTestRule.density) { 12.dp.toPx() }, text.top - box.top, 1f)
    }

    @Test
    fun `an account header that does not fit puts the server on a second line (T-307)`() {
        val account = AccountEntity(
            id = "long",
            serverUrl = "https://lists.example.org/a-rather-long-path/",
            accountId = "acct-long",
            email = "someone.with.a.long.address@example.org",
            label = "long",
            signedIn = true,
        )
        var width by mutableStateOf(400.dp)
        composeTestRule.setContent {
            Box(Modifier.width(width)) { AccountHeader(account, first = true) }
        }
        fun bounds(part: String) =
            composeTestRule.onNodeWithTag("account-header-$part-long", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue("wide: one line", bounds("server").top < bounds("email").bottom)
        // Narrower than email and server together (the test fonts measure far narrower than real ones).
        width = (bounds("server").right - bounds("email").left).let { with(composeTestRule.density) { it.toDp() } } - 10.dp
        composeTestRule.waitForIdle()

        assertTrue("narrow: server below the email", bounds("server").top >= bounds("email").bottom)
        assertEquals("server starts the line", bounds("email").left, bounds("server").left, 1f)
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
    fun `an outdated account's section says so, keeps its lists and offers to check for an update (T-304)`() = runBlocking<Unit> {
        addWorkAccount()
        accounts.registry.update("work") { it.copy(outdated = true) }
        var checks = 0
        show(onCheckForUpdate = { checks++ })

        composeTestRule.onAllNodesWithText("This server needs a newer app. Tap to check for an update.").assertCountEquals(1)
        composeTestRule.onNodeWithText("Office supplies").assertExists()
        composeTestRule.onAllNodesWithText("Signed out. Tap to sign in.").assertCountEquals(0)
        composeTestRule.onNodeWithText("This server needs a newer app. Tap to check for an update.").performClick()

        assertEquals(1, checks)
    }

    @Test
    fun `the local area's section, "On this phone", sits in the user's order, its cards marked by the phone glyph (T-293, T-309)`() = runBlocking<Unit> {
        val local = accounts.registry.addLocal()!!
        listsRepo.create(local.id, "Hardware")
        addWorkAccount()
        show()

        composeTestRule.onNodeWithTag("account-header-${local.id}").assertExists()
        composeTestRule.onNodeWithText("On this phone").assertExists()
        // Added before the work account, so it comes before it.
        val headers = listOf(TEST_ACCOUNT_ID, local.id, "work").map { id ->
            composeTestRule.onNodeWithTag("account-header-$id").fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("in the user's order, the local area among the server accounts", headers.sorted(), headers)
    }

    @Test
    fun `with only the local area the overview is the plain one-account screen (T-293)`() = runBlocking<Unit> {
        accounts.registry.remove(TEST_ACCOUNT_ID)
        val local = accounts.registry.addLocal()!!
        listsRepo.create(local.id, "Hardware")
        show()

        composeTestRule.onNodeWithText("Hardware").assertExists()
        composeTestRule.onAllNodesWithTag("account-header-${local.id}").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("On this phone").assertCountEquals(0)
    }

    // ---- the server message (T-315) ----------------------------------------------------

    private fun top(tag: String) =
        composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().top

    private fun topOfText(text: String) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().top

    @Test
    fun `one account shows its server's message above the lists, as plain text (T-315)`() = runBlocking<Unit> {
        val message = "Maintenance Sunday, see https://status.example.com"
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverMessage = message) }
        show()

        val node = composeTestRule.onNodeWithTag("server-message-$TEST_ACCOUNT_ID", useUnmergedTree = true)
        node.assertTextEquals(message)
        org.p23q.shoppinglist.ui.login.assertNoLinks(node)
        assertTrue("above the lists", top("server-message-$TEST_ACCOUNT_ID") < topOfText("Groceries"))
    }

    @Test
    fun `with several accounts each server's message sits under that account's heading (T-315)`() = runBlocking<Unit> {
        addWorkAccount()
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverMessage = "Home is moving") }
        accounts.registry.update("work") { it.copy(serverMessage = "Work is full") }
        show()

        composeTestRule.onNodeWithTag("server-message-$TEST_ACCOUNT_ID", useUnmergedTree = true).assertTextEquals("Home is moving")
        composeTestRule.onNodeWithTag("server-message-work", useUnmergedTree = true).assertTextEquals("Work is full")
        val home = top("account-header-$TEST_ACCOUNT_ID")
        val work = top("account-header-work")
        val (first, second) = if (home < work) TEST_ACCOUNT_ID to "work" else "work" to TEST_ACCOUNT_ID
        val firstList = if (first == "work") "Office supplies" else "Groceries"
        val secondList = if (second == "work") "Office supplies" else "Groceries"
        assertTrue(top("account-header-$first") < top("server-message-$first"))
        assertTrue(top("server-message-$first") < topOfText(firstList))
        assertTrue(topOfText(firstList) < top("account-header-$second"))
        assertTrue(top("account-header-$second") < top("server-message-$second"))
        assertTrue(top("server-message-$second") < topOfText(secondList))
    }

    @Test
    fun `an account whose server has no message shows none (T-315)`() = runBlocking<Unit> {
        addWorkAccount()
        accounts.registry.update("work") { it.copy(serverMessage = "Work is full") }
        show()

        composeTestRule.onAllNodesWithTag("server-message-$TEST_ACCOUNT_ID", useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("server-message-work", useUnmergedTree = true).assertCountEquals(1)
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

    @Test
    fun `a missing name or currency puts its field in error with the reason (T-307)`() {
        showDialog(
            OverviewUiState(
                accounts = listOf(testAccount()),
                newListAccountId = TEST_ACCOUNT_ID,
                newListKind = org.p23q.shoppinglist.core.ListKind.EXPENSES,
                newListNameMissing = true,
                newListCurrencyMissing = true,
            ),
        )

        composeTestRule.onNodeWithText("Enter a name.").assertExists()
        composeTestRule.onNodeWithText("Enter a currency.").assertExists()
        composeTestRule.onNodeWithTag("new-list-name").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeTestRule.onNodeWithTag("new-list-currency").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `without a missing field the dialog shows no error`() {
        showDialog(OverviewUiState(accounts = listOf(testAccount()), newListAccountId = TEST_ACCOUNT_ID))

        composeTestRule.onNodeWithText("Enter a name.").assertDoesNotExist()
        composeTestRule.onNodeWithTag("new-list-name").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }
}
