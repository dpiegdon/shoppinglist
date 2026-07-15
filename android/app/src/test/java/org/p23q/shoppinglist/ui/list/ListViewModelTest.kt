package org.p23q.shoppinglist.ui.list

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.sync.SyncResult
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var sessionState: FakeSessionState
    private lateinit var listId: String
    private lateinit var server: MockWebServer
    private lateinit var apiProvider: ApiProvider
    private val syncStatus = SyncStatus()
    private val syncer = RecordingSyncer()

    private class RecordingSyncer : Syncer {
        var calls = 0
        override suspend fun syncNow(fullLists: List<String>): SyncResult {
            calls++
            return SyncResult.Success(0, 0, 0, 0)
        }
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        sessionState = FakeSessionState()
        listId = listsRepo.createList("Groceries")

        // T-64: ListViewModel fetches the member roster on init. Most tests here don't care about
        // it, so the default dispatcher answers every request with an empty roster; a test that
        // does care overrides server.dispatcher before calling newViewModel().
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}""")
        }
        server.start()
        val serverConfigFile = File.createTempFile("list_vm_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, SessionEvents()),
            json = json,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newViewModel(defaultCurrencyState: DefaultCurrencyState = DefaultCurrencyState(sessionState)): ListViewModel =
        ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            syncer,
            syncStatus,
            defaultCurrencyState,
            apiProvider,
        )

    @Test
    fun `groups follow category_order, then leftover categories alphabetically, uncategorized last`() = runTest {
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "bakery") }
        itemsRepo.createItem(listId, "Soap").also { itemsRepo.setCategory(it, "hygiene") }
        itemsRepo.createItem(listId, "Nails").also { itemsRepo.setCategory(it, "hardware") }
        itemsRepo.createItem(listId, "Loose item")

        val groups = newViewModel().uiState.first { it.groups.isNotEmpty() }.groups

        assertEquals(listOf("dairy", "bakery", "hardware", "hygiene", null), groups.map { it.category })
    }

    @Test
    fun `items sort alphabetically within a category`() = runTest {
        itemsRepo.createItem(listId, "Zucchini").also { itemsRepo.setCategory(it, "produce") }
        itemsRepo.createItem(listId, "apple").also { itemsRepo.setCategory(it, "produce") }
        itemsRepo.createItem(listId, "Banana").also { itemsRepo.setCategory(it, "produce") }

        val groups = newViewModel().uiState.first { it.groups.isNotEmpty() }.groups

        assertEquals(listOf("apple", "Banana", "Zucchini"), groups.single().items.map { it.name.value })
    }

    @Test
    fun `backlog items never appear regardless of the show-checked toggle`() = runTest {
        itemsRepo.createItem(listId, "Someday item", status = Status.BACKLOG)
        val viewModel = newViewModel()

        val hiddenState = viewModel.uiState.first { it.listName == "Groceries" }
        assertTrue(hiddenState.groups.none { group -> group.items.any { it.name.value == "Someday item" } })

        viewModel.toggleShowChecked()

        assertTrue(viewModel.uiState.value.groups.none { group -> group.items.any { it.name.value == "Someday item" } })
    }

    @Test
    fun `checked items are hidden by default and appear once the toggle is on`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val viewModel = newViewModel()

        val initial = viewModel.uiState.first { it.listName == "Groceries" }
        assertTrue(initial.groups.none { g -> g.items.any { it.id == itemId } })

        viewModel.toggleShowChecked()

        val afterToggle = viewModel.uiState.first { state -> state.groups.any { g -> g.items.any { it.id == itemId } } }
        assertTrue(afterToggle.showChecked)
    }

    @Test
    fun `checking an item with show-checked on never duplicates it across groups (crash regression)`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = newViewModel()
        viewModel.toggleShowChecked()
        viewModel.uiState.first { it.groups.isNotEmpty() }

        viewModel.checkOff(itemId).join()

        val checkedState = viewModel.uiState.first { s ->
            s.groups.flatMap { it.items }.firstOrNull { it.id == itemId }?.status?.value == Status.CHECKED.wireValue
        }
        // Exactly one occurrence, and no id appears twice anywhere — the transient that a duplicate
        // LazyColumn key crashed on can't happen now that todo/checked come from one snapshot.
        assertEquals(1, checkedState.groups.flatMap { it.items }.count { it.id == itemId })
        val allIds = checkedState.groups.flatMap { it.items }.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun `checkOff marks todo item checked and arms the undo snackbar`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.groups.isNotEmpty() }

        viewModel.checkOff(itemId).join()

        assertEquals(Status.CHECKED.wireValue, itemsRepo.getById(itemId)!!.status.value)
        assertEquals(itemId, viewModel.uiState.value.undoItemId)
        assertEquals("Milk", viewModel.uiState.value.undoItemName)
    }

    @Test
    fun `undoCheckOff restores the item to todo and clears the snackbar`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.groups.isNotEmpty() }
        viewModel.checkOff(itemId).join()

        viewModel.undoCheckOff().join()

        assertEquals(Status.TODO.wireValue, itemsRepo.getById(itemId)!!.status.value)
        assertNull(viewModel.uiState.value.undoItemId)
    }

    @Test
    fun `uncheck reverts a checked item back to todo`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.listName == "Groceries" }

        viewModel.uncheck(itemId).join()

        assertEquals(Status.TODO.wireValue, itemsRepo.getById(itemId)!!.status.value)
    }

    @Test
    fun `checkedCount counts checked items regardless of the show-checked toggle (T-35)`() = runTest {
        itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = newViewModel()

        assertEquals(2, viewModel.uiState.first { it.checkedCount == 2 }.checkedCount)

        // Toggling show-checked changes visibility, not the count that drives the Clear-checked action.
        viewModel.toggleShowChecked()
        assertEquals(2, viewModel.uiState.value.checkedCount)
    }

    @Test
    fun `clearChecked moves every checked item to backlog and arms the undo (T-35)`() = runTest {
        val a = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val b = itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.checkedCount == 2 }

        viewModel.clearChecked().join()

        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(a)!!.status.value)
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(b)!!.status.value)
        assertEquals(setOf(a, b), viewModel.uiState.value.clearedCheckedIds.toSet())
        // Nothing checked any more -> the action's count drops to 0 (button hides).
        assertEquals(0, viewModel.uiState.first { it.checkedCount == 0 }.checkedCount)
    }

    @Test
    fun `refresh runs a sync and clears the refreshing flag (T-36)`() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.listName == "Groceries" }

        viewModel.refresh().join()

        assertEquals(1, syncer.calls)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `sync status flows into the list ui state (T-47)`() = runTest {
        val viewModel = newViewModel()

        syncStatus.succeeded(at = 5_000L, pending = 1, blocked = 0)

        assertEquals(5_000L, viewModel.uiState.first { it.sync.lastSyncAt == 5_000L }.sync.lastSyncAt)
    }

    @Test
    fun `undoClearChecked puts the cleared items back to checked (T-35)`() = runTest {
        val a = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.checkedCount == 1 }
        viewModel.clearChecked().join()
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(a)!!.status.value)

        viewModel.undoClearChecked().join()

        assertEquals(Status.CHECKED.wireValue, itemsRepo.getById(a)!!.status.value)
        assertTrue(viewModel.uiState.value.clearedCheckedIds.isEmpty())
    }

    @Test
    fun `price renders with the item currency, falling back to the account default when absent`() = runTest {
        val withCurrency = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setPrice(withCurrency, amount = "1.99", currency = "EUR")
        val withoutCurrency = itemsRepo.createItem(listId, "Bread")
        itemsRepo.setPrice(withoutCurrency, amount = "2.50", currency = null)
        sessionState.defaultCurrency = "USD"

        val groups = newViewModel().uiState.first { it.groups.isNotEmpty() }.groups
        val items = groups.flatMap { it.items }.associateBy { it.name.value }

        assertEquals("1.99 EUR", formatPrice(items.getValue("Milk"), "USD"))
        assertEquals("2.50 USD", formatPrice(items.getValue("Bread"), "USD"))
    }

    @Test
    fun `a currency change made in Settings is reflected by the next list view (A10)`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Bread")
        itemsRepo.setPrice(itemId, amount = "2.50", currency = null)
        sessionState.defaultCurrency = "USD"
        val beforeSettingsChange = newViewModel().uiState.first { it.groups.isNotEmpty() }
        assertEquals("USD", beforeSettingsChange.defaultCurrency)

        // Simulates SettingsViewModel.updateCurrency()'s effect: it writes straight through to the
        // same SessionState this app-wide singleton represents, not a copy - so any ListViewModel
        // constructed afterwards (i.e. next time the user opens a list) picks it up automatically.
        sessionState.defaultCurrency = "EUR"

        val afterSettingsChange = newViewModel().uiState.first { it.groups.isNotEmpty() }
        assertEquals("EUR", afterSettingsChange.defaultCurrency)
        val item = afterSettingsChange.groups.flatMap { it.items }.single { it.id == itemId }
        assertEquals("2.50 EUR", formatPrice(item, afterSettingsChange.defaultCurrency))
    }

    @Test
    fun `a currency change reflects immediately in an already-open list, not just the next one (T-55)`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Bread")
        itemsRepo.setPrice(itemId, amount = "2.50", currency = null)
        sessionState.defaultCurrency = "USD"
        val defaultCurrencyState = DefaultCurrencyState(sessionState)
        val viewModel = newViewModel(defaultCurrencyState)
        viewModel.uiState.first { it.groups.isNotEmpty() }
        assertEquals("USD", viewModel.uiState.value.defaultCurrency)

        // Simulates SettingsViewModel.updateCurrency()'s effect on the SAME app-wide singleton this
        // already-open ListViewModel is observing - no need to recreate the screen (T-55).
        defaultCurrencyState.set("EUR")

        val updated = viewModel.uiState.first { it.defaultCurrency == "EUR" }
        val item = updated.groups.flatMap { it.items }.single { it.id == itemId }
        assertEquals("2.50 EUR", formatPrice(item, updated.defaultCurrency))
    }

    @Test
    fun `the list name updates live on rename, without recreating the view model (T-34)`() = runTest {
        val viewModel = newViewModel()
        assertEquals("Groceries", viewModel.uiState.first { it.listName == "Groceries" }.listName)

        listsRepo.rename(listId, "Weekly shop")

        assertEquals("Weekly shop", viewModel.uiState.first { it.listName == "Weekly shop" }.listName)
    }

    @Test
    fun `changing category_order re-groups the open list live (T-34)`() = runTest {
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "bakery") }
        val viewModel = newViewModel()
        // No explicit order yet -> alphabetical.
        assertEquals(
            listOf("bakery", "dairy"),
            viewModel.uiState.first { it.groups.size == 2 }.groups.map { it.category },
        )

        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))

        assertEquals(
            listOf("dairy", "bakery"),
            viewModel.uiState
                .first { s -> s.groups.map { it.category } == listOf("dairy", "bakery") }
                .groups.map { it.category },
        )
    }

    @Test
    fun `the member roster loads into state on init (T-64)`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody(
                """{"members": [""" +
                    """{"account_id": "acc-a", "email": "a@example.com", "initials": "A", "joined_at": 1},""" +
                    """{"account_id": "acc-b", "email": "b@example.com", "initials": "B", "joined_at": 2}""" +
                    """], "invites": []}""",
            )
        }

        val members = newViewModel().uiState.first { it.members.isNotEmpty() }.members

        assertEquals(listOf("acc-a", "acc-b"), members.map { it.accountId })
        assertEquals(listOf("A", "B"), members.map { it.initials })
    }

    @Test
    fun `an offline member-roster fetch leaves members empty rather than crashing (T-64)`() = runTest {
        // A dedicated, never-started server: any request against it fails to connect, without
        // touching the shared server/apiProvider the other tests (and tearDown) depend on.
        val unreachable = MockWebServer()
        val serverConfigFile = File.createTempFile("list_vm_offline_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(unreachable.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val offlineApiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, SessionEvents()),
            json = json,
        )

        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            syncer,
            syncStatus,
            DefaultCurrencyState(sessionState),
            offlineApiProvider,
        )
        // Give the failed fetch a chance to run; nothing to await on success, so just confirm the
        // view model is otherwise fully usable (the exception didn't propagate and crash init).
        viewModel.uiState.first { it.listName == "Groceries" }

        assertTrue(viewModel.uiState.value.members.isEmpty())
    }
}
