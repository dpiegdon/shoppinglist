package org.p23q.shoppinglist.ui.overview

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.sync.SyncResult
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OverviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var listsRepo: ListsRepo
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var sessionState: FakeSessionState
    private lateinit var syncStatus: SyncStatus
    private var syncCalls = 0
    private lateinit var viewModel: OverviewViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        sessionState = FakeSessionState()
        syncStatus = SyncStatus()
        val syncer = Syncer {
            syncCalls++
            SyncResult.Success(0, 0, 0, 0)
        }
        viewModel = OverviewViewModel(listsRepo, itemsRepo, sessionState, syncer, syncStatus)
    }

    @Test
    fun `created list appears in state, is dirty, and closes the dialog`() = runTest {
        viewModel.onNewListNameChange("Groceries")

        viewModel.createList()?.join()

        val lists = viewModel.uiState.first { it.lists.isNotEmpty() }.lists
        assertEquals(1, lists.size)
        assertEquals("Groceries", lists.first().name.value)
        assertTrue(lists.first().dirty)
        assertFalse(viewModel.uiState.value.isCreateDialogOpen)
        assertEquals("", viewModel.uiState.value.newListName)
    }

    @Test
    fun `blank name does not create a list`() = runTest {
        viewModel.onNewListNameChange("   ")

        val job = viewModel.createList()

        assertNull(job)
        assertTrue(viewModel.uiState.value.lists.isEmpty())
    }

    @Test
    fun `openList persists lastOpenedListId in session state`() = runTest {
        viewModel.onNewListNameChange("Groceries")
        viewModel.createList()?.join()
        val listId = viewModel.uiState.first { it.lists.isNotEmpty() }.lists.first().id

        viewModel.openList(listId)

        assertEquals(listId, sessionState.lastOpenedListId)
    }

    @Test
    fun `openCreateDialog and dismissCreateDialog toggle dialog visibility`() = runTest {
        viewModel.openCreateDialog()
        assertTrue(viewModel.uiState.value.isCreateDialogOpen)

        viewModel.dismissCreateDialog()
        assertFalse(viewModel.uiState.value.isCreateDialogOpen)
    }

    @Test
    fun `sync status flows into the ui state (T-47)`() = runTest {
        syncStatus.succeeded(at = 1_000L, pending = 2, blocked = 0)

        val state = viewModel.uiState.first { it.sync.lastSyncAt == 1_000L }
        assertEquals(2, state.sync.pendingCount)
        assertNull(state.attentionListId)
    }

    @Test
    fun `refresh runs a sync and clears the refreshing flag (T-36)`() = runTest {
        viewModel.refresh().join()

        assertEquals(1, syncCalls)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `a quarantined row surfaces its list for the attention banner (T-47)`() = runTest {
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        db.itemDao().blockRow(itemId)

        syncStatus.failed("bad row", pending = 0, blocked = 1)

        val state = viewModel.uiState.first { it.sync.blockedCount == 1 }
        assertEquals(listId, state.attentionListId)
    }
}
