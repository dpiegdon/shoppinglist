package org.p23q.shoppinglist.ui.redeem

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.PendingInvite
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListsRepo
import org.p23q.shoppinglist.ui.login.LoginMode
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * Which account an invite goes to (T-292): the accounts whose server URL is the link's prefix,
 * never the host alone; one redeems, several ask, none signs in to that server first.
 */
@RunWith(RobolectricTestRunner::class)
class RedeemRoutingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private val holder = PendingInviteHolder()
    private val viewModels = mutableListOf<RedeemViewModel>()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    /** The account each redeemed list was pulled for. */
    private val joinedFor = mutableListOf<String>()

    @Before
    fun setUp() {
        // One host, two instances under it: prod and stage, as the design's example has them.
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.path.orEmpty().endsWith("/api/v1/invites/redeem")) {
                    MockResponse().setBody("""{"list_id": "list-42"}""")
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"error": "not_found"}""")
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        accounts = TestAccounts(db)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private val prod get() = server.url("/prod/").toString()
    private val stage get() = server.url("/stage/").toString()

    private fun link(base: String, token: String = "tok-1") = "${base}invite/$token"

    private suspend fun addProd(token: String? = "tok-prod") =
        accounts.add(prod, id = "prod", token = token, accountId = "acct-prod", email = "me@example.com")

    private suspend fun addStage(id: String = "stage", accountId: String = "acct-stage", email: String = "me@stage.example") =
        accounts.add(stage, id = id, token = "tok-$id", accountId = accountId, email = email)

    private fun newViewModel(): RedeemViewModel {
        val listsRepo = testListsRepo(db)
        val syncer = object : Syncer {
            override suspend fun syncNow(fullLists: List<String>) = SyncResult.Success(0, 0, 0, 0)

            // What a pull after joining does: the account now holds a row of the list.
            override suspend fun syncJoined(accountId: String, serverListId: String): SyncResult {
                joinedFor += accountId
                val localId = listsRepo.create(accountId, "Shared")
                db.listDao().upsert(db.listDao().get(localId)!!.copy(serverId = serverListId, dirty = false))
                return SyncResult.Success(0, 0, 0, 0)
            }
        }
        return RedeemViewModel(accounts.registry, accounts.sessions, syncer, holder, listsRepo).also(viewModels::add)
    }

    private fun redeemPaths() = requests.map { it.path }.filter { it.orEmpty().endsWith("/invites/redeem") }

    @Test
    fun `a link no account's server matches signs in to that server first, keeping the invite`() = runTest(mainDispatcherRule.dispatcher) {
        addProd()
        val viewModel = newViewModel()
        viewModel.onTokenChange(link(stage))

        assertNull(viewModel.redeem())

        assertEquals(Routes.login(LoginMode.ADD, serverUrl = stage), viewModel.uiState.value.needsLogin)
        assertEquals(PendingInvite("tok-1", null, link(stage), LoginMode.ADD), holder.consumeFor(LoginMode.ADD, "new"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `with no account at all the invite goes through the start screen`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.onTokenChange(link(prod))

        viewModel.redeem()

        assertEquals(Routes.login(LoginMode.START, serverUrl = prod), viewModel.uiState.value.needsLogin)
    }

    @Test
    fun `one matching account redeems there, on its own server`() = runTest(mainDispatcherRule.dispatcher) {
        addProd()
        addStage()
        val viewModel = newViewModel()
        viewModel.onTokenChange("tok-1")

        viewModel.redeem(link = link(stage))?.join()

        assertEquals(listOf("/stage/api/v1/invites/redeem"), redeemPaths())
        assertEquals("Bearer tok-stage", requests.single().getHeader("Authorization"))
        assertEquals(listOf("stage"), joinedFor)
        assertEquals(db.listDao().getByServerId("stage", "list-42")!!.localId, viewModel.uiState.value.redeemedListId)
        // Named on screen: the phone holds two.
        assertEquals("stage", viewModel.uiState.value.account?.id)
        assertTrue(viewModel.uiState.value.several)
    }

    @Test
    fun `the match is on the server's path, not its host`() = runTest(mainDispatcherRule.dispatcher) {
        // Same host and port for both; only the mount path tells them apart.
        addProd()
        addStage()
        val viewModel = newViewModel()
        viewModel.onTokenChange(link(prod))

        viewModel.redeem()?.join()

        assertEquals(listOf("/prod/api/v1/invites/redeem"), redeemPaths())
        assertEquals(listOf("prod"), joinedFor)
    }

    @Test
    fun `two accounts on the invite's server ask which, and the chosen one redeems`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        addStage(id = "stage-2", accountId = "acct-stage-2", email = "other@stage.example")
        addProd()
        val viewModel = newViewModel()
        viewModel.onTokenChange(link(stage))

        assertNull(viewModel.redeem())
        assertEquals(listOf("stage", "stage-2"), viewModel.uiState.value.choices.map { it.id })
        assertTrue(requests.isEmpty())

        viewModel.chooseAccount("stage-2")?.join()

        assertEquals("Bearer tok-stage-2", requests.single().getHeader("Authorization"))
        assertEquals(listOf("stage-2"), joinedFor)
        assertTrue(viewModel.uiState.value.choices.isEmpty())
    }

    @Test
    fun `a bare token with several accounts asks which, with one goes to it`() = runTest(mainDispatcherRule.dispatcher) {
        addProd()
        val one = newViewModel()
        one.onTokenChange("tok-1")
        one.redeem()?.join()
        assertEquals(listOf("prod"), joinedFor)

        addStage()
        val two = newViewModel()
        two.onTokenChange("tok-2")
        assertNull(two.redeem())
        assertEquals(listOf("prod", "stage"), two.uiState.value.choices.map { it.id })
    }

    @Test
    fun `a signed-out matching account is signed in again first`() = runTest(mainDispatcherRule.dispatcher) {
        addProd(token = null)
        addStage()
        val viewModel = newViewModel()
        viewModel.onTokenChange(link(prod))

        assertNull(viewModel.redeem())

        assertEquals(Routes.login(LoginMode.RESIGNIN, accountId = "prod"), viewModel.uiState.value.needsLogin)
        assertEquals(PendingInvite("tok-1", "prod", link(prod), LoginMode.RESIGNIN), holder.consumeFor(LoginMode.RESIGNIN, "prod"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an account named outright is redeemed into without asking`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        addStage(id = "stage-2", accountId = "acct-stage-2", email = "other@stage.example")
        val viewModel = newViewModel()
        viewModel.onTokenChange("tok-1")

        viewModel.redeem(link = link(stage), accountId = "stage-2")?.join()

        assertEquals(listOf("stage-2"), joinedFor)
    }

    @Test
    fun `an invite link's server is everything before its invite segment, canonical`() {
        assertEquals("https://p23q.org/", inviteServerUrl("https://p23q.org/invite/abc"))
        assertEquals("https://p23q.org/shopping/", inviteServerUrl("https://P23Q.org:443/shopping/invite/abc?x=1"))
        assertEquals("http://h:8080/a/b/", inviteServerUrl("http://h:8080/a/b/invite/abc/"))
        assertNull(inviteServerUrl("abc.def"))
        val prodAccount = testAccount(id = "p", serverUrl = "https://h.example/prod/")
        val stageAccount = testAccount(id = "s", serverUrl = "https://h.example/stage/")
        assertEquals(listOf("s"), inviteAccounts(listOf(prodAccount, stageAccount), "https://h.example/stage/invite/t").map { it.id })
        assertEquals(emptyList<String>(), inviteAccounts(listOf(prodAccount, stageAccount), "https://h.example/invite/t").map { it.id })
        assertEquals(listOf("p", "s"), inviteAccounts(listOf(prodAccount, stageAccount), null).map { it.id })
    }
}
