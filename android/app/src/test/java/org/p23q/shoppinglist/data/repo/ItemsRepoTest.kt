package org.p23q.shoppinglist.data.repo

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ItemsRepoTest {

    private lateinit var db: AppDb
    private lateinit var repo: ItemsRepo
    private lateinit var syncTrigger: FakeSyncTrigger
    private val deviceId = DeviceIdProvider { "device-1" }

    @Before
    fun setUp() {
        // Robolectric only supplies a working Context here; explicitly setting BundledSQLiteDriver
        // (real native SQLite, arm64-capable) routes Room's queries around Robolectric's own
        // SQLite shadows entirely, which lack Linux/aarch64 support (see app/build.gradle.kts).
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        syncTrigger = FakeSyncTrigger()
        repo = ItemsRepo(db.itemDao(), deviceId, syncTrigger)
    }

    @Test
    fun `createItem stamps all fields dirty with the device id`() = runTest {
        val itemId = repo.createItem(listId = "list-1", name = "Milk")

        val item = repo.getById(itemId)!!
        assertEquals("Milk", item.name.value)
        assertEquals("device-1", item.name.updatedBy)
        assertEquals(Status.TODO.wireValue, item.status.value)
        assertTrue(item.dirty)
    }

    @Test
    fun `setStatus stamps only the status field clock and marks the row dirty`() = runTest {
        val itemId = repo.createItem(listId = "list-1", name = "Milk")
        val created = repo.getById(itemId)!!
        val categoryClockBefore = created.category.updatedAt

        Thread.sleep(2)
        repo.setStatus(itemId, Status.CHECKED)

        val updated = repo.getById(itemId)!!
        assertEquals(Status.CHECKED.wireValue, updated.status.value)
        assertEquals("device-1", updated.status.updatedBy)
        assertTrue(updated.status.updatedAt > created.status.updatedAt)
        assertEquals(categoryClockBefore, updated.category.updatedAt)
        assertTrue(updated.dirty)
    }

    @Test
    fun `delete tombstones the deleted field but retains the row`() = runTest {
        val itemId = repo.createItem(listId = "list-1", name = "Milk")

        repo.delete(itemId)

        val item = repo.getById(itemId)!!
        assertTrue(item.deleted.value)
        assertEquals("Milk", item.name.value)
    }

    @Test
    fun `searchRegistry matches case-insensitively across every status`() = runTest {
        val todoId = repo.createItem(listId = "list-1", name = "Milk", status = Status.TODO)
        val backlogId = repo.createItem(listId = "list-1", name = "milk chocolate", status = Status.BACKLOG)
        repo.setStatus(todoId, Status.CHECKED)
        repo.createItem(listId = "list-1", name = "Bread")

        val results = repo.searchRegistry(listId = "list-1", nameQuery = "MILK").first()

        assertEquals(setOf(todoId, backlogId), results.map { it.id }.toSet())
    }

    @Test
    fun `itemsForListByStatus excludes other statuses, other lists, and deleted rows`() = runTest {
        val todoId = repo.createItem(listId = "list-1", name = "Milk", status = Status.TODO)
        repo.createItem(listId = "list-1", name = "Eggs", status = Status.CHECKED)
        repo.createItem(listId = "list-2", name = "Milk", status = Status.TODO)
        val deletedId = repo.createItem(listId = "list-1", name = "Bread", status = Status.TODO)
        repo.delete(deletedId)

        val results = repo.itemsForListByStatus(listId = "list-1", status = Status.TODO).first()

        assertEquals(listOf(todoId), results.map { it.id })
    }

    @Test
    fun `distinctCategories returns unique non-null categories for the list`() = runTest {
        val milkId = repo.createItem(listId = "list-1", name = "Milk")
        repo.setCategory(milkId, "dairy")
        val cheeseId = repo.createItem(listId = "list-1", name = "Cheese")
        repo.setCategory(cheeseId, "dairy")
        val breadId = repo.createItem(listId = "list-1", name = "Bread")
        repo.setCategory(breadId, "bakery")
        repo.createItem(listId = "list-1", name = "Uncategorized")

        val categories = repo.distinctCategories("list-1").first()

        assertEquals(setOf("dairy", "bakery"), categories.toSet())
    }

    @Test
    fun `setStores and setPrice round-trip through JSON columns`() = runTest {
        val itemId = repo.createItem(listId = "list-1", name = "Milk")

        repo.setStores(itemId, listOf("Rewe", "Aldi"))
        repo.setPrice(itemId, amount = "1.99", currency = "EUR")

        val item = repo.getById(itemId)!!
        assertEquals(listOf("Rewe", "Aldi"), repo.decodeStores(item.stores.value))
        val price = repo.decodePrice(item.price.value)
        assertEquals("1.99", price?.amount)
        assertEquals("EUR", price?.currency)
    }

    @Test
    fun `every mutation schedules a sync`() = runTest {
        val itemId = repo.createItem(listId = "list-1", name = "Milk")
        assertEquals(1, syncTrigger.scheduleCount)

        repo.setStatus(itemId, Status.CHECKED)
        assertEquals(2, syncTrigger.scheduleCount)

        repo.delete(itemId)
        assertEquals(3, syncTrigger.scheduleCount)
    }

    @Test
    fun `dirtyRows returns only dirty rows and clearDirty clears them`() = runTest {
        val itemId = repo.createItem(listId = "list-1", name = "Milk")

        assertTrue(repo.dirtyRows().any { it.id == itemId })

        repo.clearDirty(listOf(itemId))

        assertFalse(repo.dirtyRows().any { it.id == itemId })
    }
}
