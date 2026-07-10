package org.p23q.shoppinglist.ui.listprops

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Note: ListPropsViewModel does NOT fetch members/invites in init - only loadMembers() (called
 * explicitly by the screen, mirroring SettingsViewModel.loadSessions()) hits the network, so most
 * tests below never need to enqueue a members response at all.
 */
@RunWith(RobolectricTestRunner::class)
class ListPropsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var apiProvider: ApiProvider
    private lateinit var listId: String

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        listId = listsRepo.createList("Groceries")

        val serverConfigFile = File.createTempFile("listprops_vm_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())

        val sessionState = FakeSessionState().apply { token = "tok-123" }
        val json = Json { ignoreUnknownKeys = true }
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun newViewModel(): ListPropsViewModel =
        ListPropsViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), listsRepo, itemsRepo, apiProvider)

    @Test
    fun `initial state loads the name and merges category_order with distinct categories, without a network call`() = runTest {
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Nails").also { itemsRepo.setCategory(it, "hardware") }

        val state = newViewModel().uiState.first { it.name.isNotBlank() }

        assertEquals("Groceries", state.name)
        assertEquals(listOf("dairy", "bakery", "hardware"), state.categoryOrder)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `saveName persists a rename as an LWW edit`() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        viewModel.onNameChange("Weekly Groceries")
        viewModel.saveName().join()

        assertEquals("Weekly Groceries", listsRepo.getById(listId)!!.name.value)
    }

    @Test
    fun `moveCategoryUp then saveCategoryOrder persists the new order as an LWW edit`() = runTest {
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "bakery") }
        val viewModel = newViewModel()
        val loaded = viewModel.uiState.first { it.categoryOrder.isNotEmpty() }
        val originalClock = listsRepo.getById(listId)!!.categoryOrder.updatedAt
        assertEquals(listOf("dairy", "bakery"), loaded.categoryOrder)

        viewModel.moveCategoryUp(1)
        assertEquals(listOf("bakery", "dairy"), viewModel.uiState.value.categoryOrder)

        viewModel.saveCategoryOrder().join()

        val saved = listsRepo.getById(listId)!!
        assertEquals(listOf("bakery", "dairy"), listsRepo.decodeCategoryOrder(saved.categoryOrder.value))
        assertTrue(saved.categoryOrder.updatedAt >= originalClock)
        assertTrue(saved.dirty)
    }

    @Test
    fun `loadMembers populates members and pending invites`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"members": [{"email": "a@example.com", "joined_at": 1}],""" +
                    """"invites": [{"id": "inv-1", "invited_email": "b@example.com", "expires_at": 2}]}""",
            ),
        )

        val viewModel = newViewModel()
        viewModel.loadMembers().join()

        assertEquals(listOf("a@example.com"), viewModel.uiState.value.members.map { it.email })
        assertEquals(listOf("b@example.com"), viewModel.uiState.value.pendingInvites.map { it.invitedEmail })
    }

    @Test
    fun `loadMembers failure surfaces an offline notice instead of crashing`() = runTest {
        server.shutdown()

        val viewModel = newViewModel()
        viewModel.loadMembers().join()

        assertNotNull(viewModel.uiState.value.membersError)
    }

    @Test
    fun `sendInvite success shares the URL and refreshes members`() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        viewModel.onInviteEmailChange("friend@example.com")
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"invite_id": "inv-1", "token": "tok", "url": "https://host/invite/tok", "expires_at": 1}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"members": [], "invites": [{"id": "inv-1", "invited_email": "friend@example.com", "expires_at": 1}]}""",
            ),
        )

        viewModel.sendInvite()?.join()

        assertEquals("https://host/invite/tok", viewModel.uiState.value.inviteShareUrl)
        assertEquals("", viewModel.uiState.value.inviteEmail)
        assertEquals(listOf("friend@example.com"), viewModel.uiState.value.pendingInvites.map { it.invitedEmail })
    }

    @Test
    fun `sendInvite with a blank email is rejected locally without a network call`() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        val job = viewModel.sendInvite()

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `revokeInvite calls the server then refreshes the list`() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        viewModel.revokeInvite("inv-1").join()

        assertTrue(viewModel.uiState.value.pendingInvites.isEmpty())
    }

    @Test
    fun `confirmLeave calls the server and hard-deletes the list and its items locally`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        viewModel.requestLeave()
        assertTrue(viewModel.uiState.value.isLeaveConfirmOpen)
        server.enqueue(MockResponse().setResponseCode(204))

        viewModel.confirmLeave().join()

        assertTrue(viewModel.uiState.value.hasLeft)
        assertFalse(viewModel.uiState.value.isLeaveConfirmOpen)
        assertNull(listsRepo.getById(listId))
        assertNull(itemsRepo.getById(itemId))
    }

    @Test
    fun `confirmLeave still cleans up locally even if the server is unreachable`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.shutdown()

        viewModel.confirmLeave().join()

        assertTrue(viewModel.uiState.value.hasLeft)
        assertNull(listsRepo.getById(listId))
        assertNull(itemsRepo.getById(itemId))
    }
}
