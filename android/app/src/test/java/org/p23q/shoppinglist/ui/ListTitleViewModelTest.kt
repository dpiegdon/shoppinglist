package org.p23q.shoppinglist.ui

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListTitleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var listsRepo: ListsRepo

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        runTest(mainDispatcherRule.dispatcher) { db.insertTestAccount() }
        listsRepo = ListsRepo(db, DeviceIdProvider { "device-1" }, FakeSyncTrigger())
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun newViewModel(listId: String) =
        ListTitleViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), listsRepo, testListAccounts(db, listsRepo))

    @Test
    fun `no subtitle with one account (T-292)`() = runTest(mainDispatcherRule.dispatcher) {
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val viewModel = newViewModel(listId)
        viewModel.name.first { it == "Groceries" }

        // Collected, so the flow has run: it stays null rather than never having been computed.
        val seen = mutableListOf<String?>()
        val job = launch { viewModel.subtitle.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun `with two accounts the subtitle is the list's own account's email (T-292)`() = runTest(mainDispatcherRule.dispatcher) {
        db.insertTestAccount(testAccount(id = "second", accountId = "acct-2", email = "work@example.com", serverUrl = "https://work.example.test/"))
        val mine = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val theirs = listsRepo.create("second", "Office")

        assertEquals("me@example.com", newViewModel(mine).subtitle.first { it != null })
        assertEquals("work@example.com", newViewModel(theirs).subtitle.first { it != null })
    }

    @Test
    fun `name exposes the list name and updates live on rename`() = runTest(mainDispatcherRule.dispatcher) {
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val viewModel = newViewModel(listId)

        assertEquals("Groceries", viewModel.name.first { it == "Groceries" })

        listsRepo.rename(listId, "Weekly shop")

        assertEquals("Weekly shop", viewModel.name.first { it == "Weekly shop" })
    }
}
