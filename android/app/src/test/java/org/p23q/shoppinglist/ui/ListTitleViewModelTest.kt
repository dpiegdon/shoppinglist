package org.p23q.shoppinglist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ListsRepo
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
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        listsRepo = ListsRepo(db.listDao(), DeviceIdProvider { "device-1" }, FakeSyncTrigger())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `name exposes the list name and updates live on rename`() = runTest {
        val listId = listsRepo.createList("Groceries")
        val viewModel = ListTitleViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), listsRepo)

        assertEquals("Groceries", viewModel.name.first { it == "Groceries" })

        listsRepo.rename(listId, "Weekly shop")

        assertEquals("Weekly shop", viewModel.name.first { it == "Weekly shop" })
    }
}
