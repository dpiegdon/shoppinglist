package org.p23q.shoppinglist.ui.accounts

import org.p23q.shoppinglist.data.runCurrentOn
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.SyncState
import org.p23q.shoppinglist.data.TestAccounts
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
        runBlocking {
            accounts.registry.add(accountRow("prod"))
            accounts.registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/", signedIn = false))
            accounts.registry.add(accountRow("old", serverUrl = "https://old.example.test/", outdated = true))
            accounts.registry.add(localAccountRow())
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, runCurrentOn(mainDispatcherRule.dispatcher), viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
    }

    private fun newViewModel() = AccountsViewModel(accounts.registry, accounts.syncStatus).also { viewModels += it }

    private suspend fun AccountsViewModel.ids() = rows.first { it.isNotEmpty() }.map { it.account.id }

    @Test
    fun `one row per account in the user's order, each with what it is`() = runTest(mainDispatcherRule.dispatcher) {
        val rows = newViewModel().rows.first { it.isNotEmpty() }

        assertEquals(listOf("prod", "stage", "old", "local"), rows.map { it.account.id })
        assertEquals(
            listOf(AccountStatus.SIGNED_IN, AccountStatus.SIGNED_OUT, AccountStatus.OUTDATED, AccountStatus.LOCAL),
            rows.map { it.status },
        )
    }

    @Test
    fun `an outdated account reads as outdated even while signed out`() {
        assertEquals(AccountStatus.OUTDATED, accountRow("x", signedIn = false, outdated = true).status())
    }

    @Test
    fun `each row carries its own account's sync figures, not the aggregate`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.syncStatus.account("prod").succeeded(at = 1_000, pending = 0, blocked = 0)
        accounts.syncStatus.account("stage").failed("offline", pending = 3, blocked = 1)
        val viewModel = newViewModel()

        val rows = viewModel.rows.first { row -> row.any { it.sync.pendingCount == 3 } }.associateBy { it.account.id }

        assertEquals(SyncState(lastSyncAt = 1_000), rows.getValue("prod").sync)
        assertEquals(SyncState(lastError = "offline", pendingCount = 3, blockedCount = 1), rows.getValue("stage").sync)
        assertEquals(SyncState(), rows.getValue("old").sync)
    }

    @Test
    fun `moving an account down swaps it with the next one and stores the order`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.moveDown("prod").join()

        assertEquals(listOf("stage", "prod", "old", "local"), viewModel.rows.first { it.first().account.id == "stage" }.map { it.account.id })
        assertEquals(listOf("stage", "prod", "old", "local"), db.accountDao().all().map { it.id })
    }

    @Test
    fun `moving an account up swaps it with the one before`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.moveUp("old").join()

        assertEquals(listOf("prod", "old", "stage", "local"), accounts.registry.snapshot().map { it.id })
    }

    @Test
    fun `the first cannot move up and the last cannot move down`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.moveUp("prod").join()
        viewModel.moveDown("local").join()

        assertEquals(listOf("prod", "stage", "old", "local"), viewModel.ids())
    }

    @Test
    fun `the local area is listed last even when a server account was added after it (T-302)`() = runTest(mainDispatcherRule.dispatcher) {
        // Its sortOrder is below "late"'s; the overview and Copy to still show it last.
        accounts.registry.add(accountRow("late", serverUrl = "https://late.example.test/"))

        assertEquals(listOf("prod", "stage", "old", "late", "local"), newViewModel().ids())
    }

    @Test
    fun `the local area does not move, and no server account moves past it (T-302)`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.registry.add(accountRow("late", serverUrl = "https://late.example.test/"))
        val viewModel = newViewModel()

        viewModel.moveUp("local").join()
        viewModel.moveDown("late").join()
        viewModel.moveUp("late").join()

        assertEquals(listOf("prod", "stage", "late", "old", "local"), viewModel.rows.first { it[2].account.id == "late" }.map { it.account.id })
    }
}
