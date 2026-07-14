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
    fun `a new list has no notes until set`() = runTest {
        val listId = repo.createList("Groceries")

        assertEquals(null, repo.getById(listId)!!.notes.value)
    }

    @Test
    fun `setNotes stamps only the notes field clock`() = runTest {
        val listId = repo.createList("Groceries")
        val created = repo.getById(listId)!!
        val nameClockBefore = created.name.updatedAt

        Thread.sleep(2)
        repo.setNotes(listId, "Gate code: 4471")

        val updated = repo.getById(listId)!!
        assertEquals("Gate code: 4471", updated.notes.value)
        assertEquals("device-1", updated.notes.updatedBy)
        assertTrue(updated.notes.updatedAt > created.notes.updatedAt)
        assertEquals(nameClockBefore, updated.name.updatedAt)
    }

    @Test
    fun `setNotes to null clears an existing note`() = runTest {
        val listId = repo.createList("Groceries")
        repo.setNotes(listId, "temporary")

        repo.setNotes(listId, null)

        assertEquals(null, repo.getById(listId)!!.notes.value)
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

        repo.setNotes(listId, "Gate code: 4471")
        assertEquals(3, syncTrigger.scheduleCount)

        repo.delete(listId)
        assertEquals(4, syncTrigger.scheduleCount)
    }

    @Test
    fun `dirtyRows returns only dirty rows and clearDirty clears them`() = runTest {
        val listId = repo.createList("Groceries")

        assertTrue(repo.dirtyRows().any { it.id == listId })

        repo.clearDirty(listOf(listId))

        assertFalse(repo.dirtyRows().any { it.id == listId })
    }

    @Test
    fun `duplicate creates a solo-owned copy with the source's name, category order, and notes (T-63)`() = runTest {
        val sourceId = repo.createList("Groceries")
        repo.setCategoryOrder(sourceId, listOf("dairy", "bakery"))
        repo.setNotes(sourceId, "Gate code: 4471")

        val copyId = repo.duplicate(sourceId)!!

        assertTrue(copyId != sourceId)
        val copy = repo.getById(copyId)!!
        assertEquals("Groceries (Copy)", copy.name.value)
        assertEquals(listOf("dairy", "bakery"), repo.decodeCategoryOrder(copy.categoryOrder.value))
        assertEquals("Gate code: 4471", copy.notes.value)
        assertFalse(copy.deleted.value)
        assertTrue(copy.dirty)
        assertEquals("device-1", copy.name.updatedBy)
    }

    @Test
    fun `duplicate returns null for an unknown source list`() = runTest {
        val result = repo.duplicate("does-not-exist")

        assertEquals(null, result)
    }

    @Test
    fun `duplicate schedules a sync`() = runTest {
        val sourceId = repo.createList("Groceries")
        val before = syncTrigger.scheduleCount

        repo.duplicate(sourceId)

        assertEquals(before + 1, syncTrigger.scheduleCount)
    }
}
