package org.p23q.shoppinglist.ui.redeem

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.syncResponseWithList
import org.p23q.shoppinglist.data.testListsRepo
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.robolectric.RobolectricTestRunner
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText
import org.p23q.shoppinglist.ui.login.LoginMode
import org.p23q.shoppinglist.ui.Routes

@RunWith(RobolectricTestRunner::class)
class RedeemViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()

        accounts = TestAccounts(db)
        accounts.add(server.url("/").toString())
        syncEngine = accounts.syncEngine()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private fun newViewModel(holder: PendingInviteHolder = PendingInviteHolder()): RedeemViewModel =
        RedeemViewModel(accounts.registry, accounts.sessions, accounts.syncer(syncEngine), holder, testListsRepo(db))

    /** This phone's row of the list the server calls [serverId]. */
    private suspend fun localIdOf(serverId: String): String? = db.listDao().getByServerId(TEST_ACCOUNT_ID, serverId)?.localId

    @Test
    fun `redeem with a blank token is rejected locally without a network call`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        val job = viewModel.redeem()

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redeem success syncs the newly shared list and reports its local id`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncResponseWithList("list-42")))
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        // The screens open this phone's row of the list, not the server's id for it (T-299).
        val localId = localIdOf("list-42")
        assertNotNull(localId)
        assertNotEquals("list-42", localId)
        assertEquals(localId, viewModel.uiState.value.redeemedListId)
        assertNull(viewModel.uiState.value.errorMessage)
        val syncRequest = server.takeRequest()
        assertEquals("/api/v1/invites/redeem", syncRequest.path)
        val followUp = server.takeRequest()
        assertEquals("/api/v1/sync", followUp.path)
    }

    @Test
    fun `a pasted full invite URL is redeemed as its bare token (T-71)`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncResponseWithList("list-42")))
        val viewModel = newViewModel()
        viewModel.onTokenChange(server.url("/invite/abc.def").toString())

        viewModel.redeem()?.join()

        assertEquals(localIdOf("list-42"), viewModel.uiState.value.redeemedListId)
        val redeemRequest = server.takeRequest()
        assertEquals("/api/v1/invites/redeem", redeemRequest.path)
        assertTrue(redeemRequest.body.readUtf8().contains("\"abc.def\""))
    }

    @Test
    fun `a redeemed list the pull did not bring opens nothing and says the server could not be reached`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error": "unavailable", "message": "later"}"""))
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        assertNull(viewModel.uiState.value.redeemedListId)
        assertEquals(UiText.res(R.string.error_offline), viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `pastedInvite finds the link in a pasted message and leaves a bare token alone (T-300)`() {
        assertEquals("https://p23q.org/shopping/invite/abc.def", pastedInvite("Join my list: https://p23q.org/shopping/invite/abc.def"))
        assertEquals("https://p23q.org/invite/abc.def", pastedInvite("Join my list (https://p23q.org/invite/abc.def). Thanks!"))
        assertEquals(
            "https://p23q.org/invite/abc.def",
            pastedInvite("Get the app from https://p23q.org/app and join https://p23q.org/invite/abc.def"),
        )
        assertEquals("abc.def", pastedInvite("  abc.def \n"))
    }

    @Test
    fun `a pasted message with an invite link in it is redeemed as that link (T-300)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.onTokenChange("Join my list: https://p23q.org/shopping/invite/abc.def")

        assertNull(viewModel.redeem())

        // No account on that server: sign in to it, the link's own server, not the message's text.
        assertEquals(Routes.login(LoginMode.ADD, serverUrl = "https://p23q.org/shopping/"), viewModel.uiState.value.needsLogin)
    }

    @Test
    fun `extractInviteToken pulls the token out of full URLs and passes bare tokens through (T-71)`() {
        assertEquals("abc.def", extractInviteToken("abc.def"))
        assertEquals("abc.def", extractInviteToken("  abc.def  "))
        assertEquals("abc.def", extractInviteToken("https://p23q.org/invite/abc.def"))
        // Mounted under a path prefix, and with a trailing slash / query / fragment riding along.
        assertEquals("abc.def", extractInviteToken("https://example.com/shopping/invite/abc.def"))
        assertEquals("abc.def", extractInviteToken("https://p23q.org/invite/abc.def/"))
        assertEquals("abc.def", extractInviteToken("https://p23q.org/invite/abc.def?utm=x"))
        assertEquals("abc.def", extractInviteToken("https://p23q.org/invite/abc.def#frag"))
    }

    @Test
    fun `redeem failure surfaces the server's error message`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error": "invite_used", "message": "This invite was already used"}"""),
        )
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        // Translated from the error's code, not the server's English text (see ErrorText).
        assertEquals(UiText.res(R.string.api_error_invite_used), viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.redeemedListId)
    }

    @Test
    fun `redeem with the server unreachable surfaces a network error`() = runTest(mainDispatcherRule.dispatcher) {
        server.shutdown()
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.redeemedListId)
    }

    @Test
    fun `redeeming while logged out stashes the token and signals needsLogin without calling the API`() = runTest(mainDispatcherRule.dispatcher) {
        val holder = PendingInviteHolder()
        // The server rejected the token: no token, signed out.
        accounts.secrets.setToken(TEST_ACCOUNT_ID, null)
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(signedIn = false) }
        val viewModel = newViewModel(holder)
        viewModel.onTokenChange("invite-xyz")

        val job = viewModel.redeem()

        assertNull("short-circuits before launching any request", job)
        assertEquals(Routes.login(LoginMode.RESIGNIN, accountId = TEST_ACCOUNT_ID), viewModel.uiState.value.needsLogin)
        assertEquals("invite-xyz", holder.consumeFor(LoginMode.RESIGNIN, TEST_ACCOUNT_ID)?.token)
        assertEquals("no request should have reached the server", 0, server.requestCount)
    }

    @Test
    fun `beside the local area, an invite goes to the one server account and names no account (T-293)`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.registry.addLocal()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncResponseWithList("list-42")))
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        assertTrue("no choice with the local area", viewModel.uiState.value.choices.isEmpty())
        assertFalse(viewModel.uiState.value.several)
        assertEquals(localIdOf("list-42"), viewModel.uiState.value.redeemedListId)
    }

    @Test
    fun `with only the local area, an invite adds a server account rather than opening the start screen (T-293)`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.registry.remove(TEST_ACCOUNT_ID)
        accounts.registry.addLocal()
        val holder = PendingInviteHolder()
        val viewModel = newViewModel(holder)
        viewModel.onTokenChange("https://other.example.test/lists/invite/abc.def")

        assertNull(viewModel.redeem())

        assertEquals(Routes.login(LoginMode.ADD, serverUrl = "https://other.example.test/lists/"), viewModel.uiState.value.needsLogin)
        assertEquals("abc.def", holder.consumeFor(LoginMode.ADD, null)?.token)
        assertEquals(0, server.requestCount)
    }
}
