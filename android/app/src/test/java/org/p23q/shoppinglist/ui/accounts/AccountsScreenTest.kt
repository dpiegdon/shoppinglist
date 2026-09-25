package org.p23q.shoppinglist.ui.accounts

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.TestAccounts
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AccountsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private val viewModels = mutableListOf<AccountsViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        accounts = TestAccounts(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, ::idleMainLooper, viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
    }

    private val opened = mutableListOf<String>()
    private val signIns = mutableListOf<String>()
    private var added = 0
    private var updateChecks = 0

    private fun show() {
        val viewModel = AccountsViewModel(accounts.registry, accounts.syncStatus).also { viewModels += it }
        composeTestRule.setContent {
            AccountsScreen(
                onAddAccount = { added++ },
                onOpenAccount = { opened += it },
                onSignIn = { signIns += it },
                onCheckForUpdate = { updateChecks++ },
                viewModel = viewModel,
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.rows.value.size == accounts.registry.snapshot().size
        }
    }

    @Test
    fun `each account shows its email, its full server URL and its state`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod", email = "me@example.com"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/", email = "me@example.com", signedIn = false))
        accounts.registry.add(accountRow("old", serverUrl = "https://old.example.test/", outdated = true))
        accounts.registry.add(localAccountRow())

        show()

        // Prod and stage on one host tell apart by the path, so the whole URL is shown.
        composeTestRule.onNodeWithText("https://lists.example.test/").assertExists()
        composeTestRule.onNodeWithText("https://lists.example.test/stage/").assertExists()
        composeTestRule.onNodeWithText("Signed in").assertExists()
        composeTestRule.onNodeWithText("Signed out: tap to sign in").assertExists()
        composeTestRule.onNodeWithText("App too old for this server").assertExists()
        composeTestRule.onNodeWithText("Sync with this server is paused until the app is updated.").assertExists()
        composeTestRule.onNodeWithText("On this phone").assertExists()
        // No way to sign out: the server does that.
        composeTestRule.onNodeWithText("Log out").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sign out").assertDoesNotExist()
    }

    /** T-304: with another account working, the blocking update screen is not there to ask. */
    @Test
    fun `an outdated row offers to check for an update`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("old", serverUrl = "https://old.example.test/", outdated = true))

        show()
        composeTestRule.onAllNodesWithText("Check for update").assertCountEquals(1)
        composeTestRule.onNodeWithTag("account-check-update-old", useUnmergedTree = true).performClick()

        assertEquals(1, updateChecks)
        assertEquals("the row itself was not opened", emptyList<String>(), opened)
    }

    @Test
    fun `a row shows its own account's sync figures`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/"))
        accounts.syncStatus.account("prod").succeeded(at = System.currentTimeMillis() - 5 * 60_000, pending = 2, blocked = 1)

        show()

        composeTestRule.onNodeWithText("Synced 5 min ago · 2 pending").assertExists()
        composeTestRule.onNodeWithText("Items needing attention: 1").assertExists()
        // Stage has not reported yet: its own line, not prod's.
        composeTestRule.onNodeWithText("Not synced yet").assertExists()
    }

    @Test
    fun `tapping a row opens that account, and a signed-out row's line signs it in`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/", signedIn = false))

        show()
        composeTestRule.onNodeWithTag("account-row-prod").performClick()
        composeTestRule.onNodeWithTag("account-sign-in-stage", useUnmergedTree = true).performClick()

        assertEquals(listOf("prod"), opened)
        assertEquals(listOf("stage"), signIns)
    }

    @Test
    fun `Add account opens the form`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))

        show()
        composeTestRule.onNodeWithTag("accounts-add").performClick()

        assertEquals(1, added)
    }

    @Test
    fun `the handle offers the moves to accessibility services, none past either end (T-307)`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/"))

        show()
        fun actions(id: String) = composeTestRule.onNodeWithTag("account-handle-$id", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertEquals(listOf("Move down"), actions("prod").map { it.label })
        assertEquals(listOf("Move up"), actions("stage").map { it.label })

        composeTestRule.runOnUiThread { actions("prod").single().action() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            order() == listOf("stage", "prod")
        }
    }

    @Test
    fun `the local area is named in the app's language, not by its stored label (T-293)`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        // A label stored in the language of the day it was made would go stale.
        accounts.registry.add(localAccountRow(label = "Auf diesem Telefon"))

        show()

        composeTestRule.onNodeWithText("On this phone").assertExists()
        composeTestRule.onNodeWithText("Not synced, not backed up, not shared.").assertExists()
        composeTestRule.onNodeWithText("Auf diesem Telefon").assertDoesNotExist()
    }

    @Test
    fun `Add local area creates the area, shows its note once and is then no longer offered (T-293)`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))

        show()
        composeTestRule.onNodeWithTag("accounts-add-local").performClick()
        // The note follows the row's write.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodesWithText(
                "Lists here stay on this phone only. They are not backed up and cannot be shared. Sign in to a server any time to add shared lists.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("local-area-note-ok").performClick()
        composeTestRule.onNodeWithText("Lists on this phone").assertDoesNotExist()
        composeTestRule.onNodeWithTag("accounts-add-local").assertDoesNotExist()
        assertEquals(listOf("prod", accounts.registry.local()!!.id), db.accountDao().all().map { it.id })
    }

    @Test
    fun `a phone with a local area is not offered another (T-293)`() = runBlocking<Unit> {
        accounts.registry.add(localAccountRow())

        show()

        composeTestRule.onNodeWithTag("accounts-add-local").assertDoesNotExist()
        composeTestRule.onNodeWithTag("accounts-add").assertExists()
    }

    @Test
    fun `the local area has a drag handle and its moves like any account, and sits in the user's order (T-309)`() = runBlocking<Unit> {
        accounts.registry.add(localAccountRow())
        accounts.registry.add(accountRow("prod"))

        show()

        composeTestRule.onNodeWithTag("account-handle-prod", useUnmergedTree = true).assertExists()
        val localActions = composeTestRule.onNodeWithTag("account-handle-local", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertEquals(listOf("Move down"), localActions.map { it.label })
        val prodTop = composeTestRule.onNodeWithTag("account-row-prod").fetchSemanticsNode().boundsInRoot.top
        val localTop = composeTestRule.onNodeWithTag("account-row-local").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the local area, first in the user's order, is above the server account", localTop < prodTop)
    }

    private fun order() = accounts.registry.snapshot().sortedBy { it.sortOrder }.map { it.id }

    private fun dragHandle(id: String, byRows: Float) {
        val rowHeight = composeTestRule.onNodeWithTag("account-row-$id").fetchSemanticsNode().boundsInRoot.height
        composeTestRule.onNodeWithTag("account-handle-$id", useUnmergedTree = true).performTouchInput {
            down(center)
            // In small steps, as a finger moves: each swap rebases the offset by the neighbour's height.
            repeat(40) { moveBy(Offset(0f, rowHeight * byRows / 40)) }
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `dragging a server account's handle down past its neighbour swaps them, and the order is kept (T-307)`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/"))
        accounts.registry.add(accountRow("old", serverUrl = "https://old.example.test/"))

        show()
        dragHandle("prod", byRows = 1.3f)

        composeTestRule.waitUntil(timeoutMillis = 5_000) { order() == listOf("stage", "prod", "old") }
        assertEquals(listOf("stage", "prod", "old"), db.accountDao().all().sortedBy { it.sortOrder }.map { it.id })
        assertEquals("a drag is not a tap", emptyList<String>(), opened)
    }

    @Test
    fun `a server account dragged down passes the local area, and the local area drags up past one (T-309)`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/"))
        val local = accounts.registry.addLocal()!!.id

        show()
        dragHandle("stage", byRows = 1.3f)
        composeTestRule.waitUntil(timeoutMillis = 5_000) { order() == listOf("prod", local, "stage") }

        // Its card is shorter than the server account's it passes, and it is at the top after one step.
        dragHandle(local, byRows = -3f)
        composeTestRule.waitUntil(timeoutMillis = 5_000) { order() == listOf(local, "prod", "stage") }
        assertEquals(listOf(local, "prod", "stage"), db.accountDao().all().sortedBy { it.sortOrder }.map { it.id })
    }
}
