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
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListsRepoTest {

    private lateinit var db: AppDb
    private lateinit var repo: ListsRepo
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
        repo = ListsRepo(db.listDao(), deviceId, syncTrigger)
    }

    @Test
    fun `createList stamps fields dirty with the device id`() = runTest {
        val listId = repo.createList("Groceries")

        val list = repo.getById(listId)!!
        assertEquals("Groceries", list.name.value)
        assertEquals("device-1", list.name.updatedBy)
        assertTrue(list.dirty)
        assertFalse(list.deleted.value)
    }

    @Test
    fun `rename stamps only the name field clock`() = runTest {
        val listId = repo.createList("Groceries")
        val created = repo.getById(listId)!!
        val categoryOrderClockBefore = created.categoryOrder.updatedAt

        Thread.sleep(2)
        repo.rename(listId, "Weekly Groceries")

        val updated = repo.getById(listId)!!
        assertEquals("Weekly Groceries", updated.name.value)
        assertTrue(updated.name.updatedAt > created.name.updatedAt)
        assertEquals(categoryOrderClockBefore, updated.categoryOrder.updatedAt)
    }

    @Test
    fun `setCategoryOrder round-trips a list of categories through the JSON column`() = runTest {
        val listId = repo.createList("Groceries")

        repo.setCategoryOrder(listId, listOf("dairy", "bakery"))

        val list = repo.getById(listId)!!
        assertEquals(listOf("dairy", "bakery"), repo.decodeCategoryOrder(list.categoryOrder.value))
    }

    @Test
    fun `delete tombstones the list but retains the row`() = runTest {
        val listId = repo.createList("Groceries")

        repo.delete(listId)

        val list = repo.getById(listId)!!
        assertTrue(list.deleted.value)
        assertEquals("Groceries", list.name.value)
    }

    @Test
    fun `activeLists excludes deleted lists`() = runTest {
        val keepId = repo.createList("Groceries")
        val deletedId = repo.createList("Old list")
        repo.delete(deletedId)

        val active = repo.activeLists().first()

        assertEquals(listOf(keepId), active.map { it.id })
    }

    @Test
    fun `every mutation schedules a sync`() = runTest {
        val listId = repo.createList("Groceries")
        assertEquals(1, syncTrigger.scheduleCount)

        repo.rename(listId, "Weekly Groceries")
        assertEquals(2, syncTrigger.scheduleCount)

        repo.delete(listId)
        assertEquals(3, syncTrigger.scheduleCount)
    }

    @Test
    fun `dirtyRows returns only dirty rows and clearDirty clears them`() = runTest {
        val listId = repo.createList("Groceries")

        assertTrue(repo.dirtyRows().any { it.id == listId })

        repo.clearDirty(listOf(listId))

        assertFalse(repo.dirtyRows().any { it.id == listId })
    }
}
