package org.p23q.shoppinglist.ui

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RootViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        accounts = TestAccounts(db)
        runBlocking { accounts.add(server.url("/").toString()) }
    }

    private val viewModels = mutableListOf<RootViewModel>()

    @After
    fun tearDown() {
        // The view model collects the registry eagerly, and a 401 or a 426 writes the account in
        // the background: both are finished before the database goes, or the next test is blamed.
        viewModels.forEach { it.viewModelScope.cancel() }
        if (::accounts.isInitialized) runBlocking { accounts.registry.flush() }
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private fun viewModel() = RootViewModel(accounts.sessions).also { viewModels += it }

    @Test
    fun `updateRequired follows the account the server refused as outdated (T-244)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.updateRequired.collect {} }
            assertFalse(viewModel.updateRequired.value)
            server.enqueue(MockResponse().setResponseCode(426).setBody("""{"error": "client_outdated", "message": "update"}"""))

            runCatching { accounts.sessions.get(TEST_ACCOUNT_ID).api.lists() }
            advanceUntilIdle()

            // A state, not an event: the root reads the current value, so a screen recomposing
            // after the refusal still blocks instead of having missed the moment.
            assertTrue(viewModel.updateRequired.value)
        }

    @Test
    fun `one outdated account of two does not block the app, both do (T-292)`() =
        runTest(mainDispatcherRule.dispatcher) {
            accounts.add(server.url("/stage/").toString(), id = "stage-account", accountId = "acct-stage")
            val viewModel = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.updateRequired.collect {} }
            val outdated = """{"error": "client_outdated", "message": "update"}"""

            server.enqueue(MockResponse().setResponseCode(426).setBody(outdated))
            runCatching { accounts.sessions.get(TEST_ACCOUNT_ID).api.lists() }
            advanceUntilIdle()
            // The other server still accepts this build: its lists keep syncing, and the outdated
            // account says so on the Accounts screen instead of the whole app going dark.
            assertFalse(viewModel.updateRequired.value)

            server.enqueue(MockResponse().setResponseCode(426).setBody(outdated))
            runCatching { accounts.sessions.get("stage-account").api.lists() }
            advanceUntilIdle()
            assertTrue(viewModel.updateRequired.value)
        }
}
