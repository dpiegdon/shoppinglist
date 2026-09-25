package org.p23q.shoppinglist.ui.accounts

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import org.junit.After
import org.junit.Assert.assertEquals
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
        viewModels.forEach { it.viewModelScope.cancel() }
        if (::accounts.isInitialized) runBlocking { accounts.registry.flush() }
        if (::db.isInitialized) db.close()
    }

    private val opened = mutableListOf<String>()
    private val signIns = mutableListOf<String>()
    private var added = 0

    private fun show() {
        val viewModel = AccountsViewModel(accounts.registry, accounts.syncStatus).also { viewModels += it }
        composeTestRule.setContent {
            AccountsScreen(
                onAddAccount = { added++ },
                onOpenAccount = { opened += it },
                onSignIn = { signIns += it },
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
    fun `the arrows reorder the accounts, and the ends cannot move further`() = runBlocking<Unit> {
        accounts.registry.add(accountRow("prod"))
        accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/"))

        show()
        composeTestRule.onAllNodesWithContentDescription("Move up")[0].assertIsNotEnabled()
        composeTestRule.onAllNodesWithContentDescription("Move down")[1].assertIsNotEnabled()
        composeTestRule.onAllNodesWithContentDescription("Move down")[0].performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            accounts.registry.snapshot().first().id == "stage"
        }

        assertEquals(listOf("stage", "prod"), db.accountDao().all().map { it.id })
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
}
