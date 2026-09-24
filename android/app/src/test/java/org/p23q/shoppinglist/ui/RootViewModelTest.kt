package org.p23q.shoppinglist.ui

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RootViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        var clearedLocalSession: String? = null
        override suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) {}
        override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) = ""
        override suspend fun logout(accountId: String) {}
        override suspend fun clearLocalSession(accountId: String) { clearedLocalSession = accountId }
        override suspend fun removeAccount(accountId: String) {}
        override suspend fun removeOtherAccounts(keep: String) {}
        override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean = true
        override fun lastOpenedListId(): String? = null
    }

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

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private fun viewModel(repo: AuthRepository = FakeAuthRepository()) =
        RootViewModel(accounts.sessions, accounts.currentAccount, repo)

    @Test
    fun `forcedLogout fires when the current account's token is rejected`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.forcedLogout.collect { received.add(Unit) }
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "revoked"}"""))

        runCatching { accounts.sessions.get(TEST_ACCOUNT_ID).api.lists() }
        advanceUntilIdle()

        assertEquals(1, received.size)
    }

    @Test
    fun `forcedLogout ignores another account's rejected token`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.add(server.url("/other/").toString(), id = "other-account", accountId = "acct-other")
        val viewModel = viewModel()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.forcedLogout.collect { received.add(Unit) }
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "revoked"}"""))

        runCatching { accounts.sessions.get("other-account").api.lists() }
        advanceUntilIdle()

        assertEquals(0, received.size)
    }

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
    fun `onForcedLogout signs the current account out locally`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository()
        val viewModel = viewModel(repo)

        viewModel.onForcedLogout().join()

        assertEquals(TEST_ACCOUNT_ID, repo.clearedLocalSession)
    }
}
