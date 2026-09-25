package org.p23q.shoppinglist.data.repo

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.testAccount
import kotlinx.coroutines.runBlocking
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ListsRepo
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
        runBlocking { db.insertTestAccount() }
        syncTrigger = FakeSyncTrigger()
        repo = ListsRepo(db, deviceId, syncTrigger)
    }

    @Test
    fun `createList stamps fields dirty with the device id`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")

        val list = repo.getById(listId)!!
        assertEquals("Groceries", list.name.value)
        assertEquals("device-1", list.name.updatedBy)
        assertTrue(list.dirty)
        assertFalse(list.deleted.value)
    }

    @Test
    fun `rename stamps only the name field clock`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")
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
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")

        repo.setCategoryOrder(listId, listOf("dairy", "bakery"))

        val list = repo.getById(listId)!!
        assertEquals(listOf("dairy", "bakery"), repo.decodeCategoryOrder(list.categoryOrder.value))
    }

    @Test
    fun `a new list has no notes until set`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")

        assertEquals(null, repo.getById(listId)!!.notes.value)
    }

    @Test
    fun `setNotes stamps only the notes field clock`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")
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
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")
        repo.setNotes(listId, "temporary")

        repo.setNotes(listId, null)

        assertEquals(null, repo.getById(listId)!!.notes.value)
    }

    @Test
    fun `delete tombstones the list but retains the row`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")

        repo.delete(listId)

        val list = repo.getById(listId)!!
        assertTrue(list.deleted.value)
        assertEquals("Groceries", list.name.value)
    }

    @Test
    fun `activeLists excludes deleted lists`() = runTest {
        val keepId = repo.create(TEST_ACCOUNT_ID, "Groceries")
        val deletedId = repo.create(TEST_ACCOUNT_ID, "Old list")
        repo.delete(deletedId)

        val active = repo.activeLists().first()

        assertEquals(listOf(keepId), active.map { it.localId })
    }

    @Test
    fun `every mutation schedules a sync`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")
        assertEquals(1, syncTrigger.scheduleCount)

        repo.rename(listId, "Weekly Groceries")
        assertEquals(2, syncTrigger.scheduleCount)

        repo.setNotes(listId, "Gate code: 4471")
        assertEquals(3, syncTrigger.scheduleCount)

        repo.delete(listId)
        assertEquals(4, syncTrigger.scheduleCount)
    }

    @Test
    fun `a new list waits to be pushed with its account, and a clean one does not`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Groceries")

        assertTrue(db.listDao().dirtyRowsForAccount(TEST_ACCOUNT_ID).any { it.localId == listId })

        db.listDao().upsert(db.listDao().get(listId)!!.copy(dirty = false))

        assertFalse(db.listDao().dirtyRowsForAccount(TEST_ACCOUNT_ID).any { it.localId == listId })
    }

    @Test
    fun `duplicate creates a solo-owned copy with the source's name, category order, and notes (T-63)`() = runTest {
        val sourceId = repo.create(TEST_ACCOUNT_ID, "Groceries")
        repo.setCategoryOrder(sourceId, listOf("dairy", "bakery"))
        repo.setNotes(sourceId, "Gate code: 4471")

        val copyId = repo.duplicate(sourceId, "(Kopie)")!!

        assertTrue(copyId != sourceId)
        val copy = repo.getById(copyId)!!
        assertEquals("Groceries (Kopie)", copy.name.value)
        assertEquals(listOf("dairy", "bakery"), repo.decodeCategoryOrder(copy.categoryOrder.value))
        assertEquals("Gate code: 4471", copy.notes.value)
        assertFalse(copy.deleted.value)
        assertTrue(copy.dirty)
        assertEquals("device-1", copy.name.updatedBy)
    }

    @Test
    fun `duplicate returns null for an unknown source list`() = runTest {
        val result = repo.duplicate("does-not-exist", "(Copy)")

        assertEquals(null, result)
    }

    @Test
    fun `duplicate schedules a sync`() = runTest {
        val sourceId = repo.create(TEST_ACCOUNT_ID, "Groceries")
        val before = syncTrigger.scheduleCount

        repo.duplicate(sourceId, "(Copy)")

        assertEquals(before + 1, syncTrigger.scheduleCount)
    }

    @Test
    fun `a quarantined list is not pushed, but re-editing it clears the block (T-198)`() = runTest {
        val listId = repo.create(TEST_ACCOUNT_ID, "Trip")
        db.listDao().blockRow(listId)

        // Quarantined: still in the mirror and on screen, but not offered for push.
        assertEquals(1, db.listDao().blockedRowCountForAccount(TEST_ACCOUNT_ID))
        assertEquals(listId, repo.firstBlockedListId())
        assertFalse(db.listDao().dirtyRowsForAccount(TEST_ACCOUNT_ID).any { it.localId == listId })
        assertTrue(repo.getById(listId)!!.syncBlocked)

        // Editing the list (correcting whatever the server refused) clears the block and re-queues it.
        repo.rename(listId, "Trip to Rome")

        assertFalse(repo.getById(listId)!!.syncBlocked)
        assertTrue(db.listDao().dirtyRowsForAccount(TEST_ACCOUNT_ID).any { it.localId == listId })
        assertEquals(0, db.listDao().blockedRowCountForAccount(TEST_ACCOUNT_ID))
    }

    private suspend fun localArea(): String {
        db.accountDao().insert(org.p23q.shoppinglist.ui.accounts.localAccountRow("on-phone"))
        return "on-phone"
    }

    @Test
    fun `a list in the local area converts between shopping list and checklist, never to a ledger (T-293)`() = runTest {
        val listId = repo.create(localArea(), "Hardware")

        assertTrue(repo.setKind(listId, ListKind.CHECKLIST))
        assertEquals(ListKind.CHECKLIST, repo.getById(listId)!!.kind.value)
        val before = repo.getById(listId)!!

        assertFalse(repo.setKind(listId, ListKind.EXPENSES))

        assertEquals("nothing written", before, repo.getById(listId))
    }

    @Test
    fun `the local area creates no ledger (T-293)`() = runTest {
        val local = localArea()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.create(local, "Trip", ListKind.EXPENSES, currency = "EUR") }
        }
        assertTrue(repo.activeLists().first().isEmpty())
        // A server account still does.
        val ledger = repo.create(TEST_ACCOUNT_ID, "Trip", ListKind.EXPENSES, currency = "EUR")
        assertEquals(ListKind.EXPENSES, repo.getById(ledger)!!.kind.value)
    }

    @Test
    fun `a local list carries this device's id and field clocks like any other (T-293)`() = runTest {
        val listId = repo.create(localArea(), "Hardware")
        repo.rename(listId, "Tools")

        val list = repo.getById(listId)!!
        assertEquals("device-1", list.name.updatedBy)
        assertTrue(list.name.updatedAt > 0)
        assertEquals("device-1", list.kind.updatedBy)
    }

    /** A second server account and the local area beside the test's own (T-294). */
    private suspend fun addOtherAccounts() {
        db.insertTestAccount(testAccount(id = "work", serverUrl = "https://work.example.test/", accountId = "acct-work", email = "me@work.example"))
        db.insertTestAccount(
            AccountEntity(id = "phone", kind = AccountEntity.KIND_LOCAL, serverUrl = null, accountId = null, email = null, label = "", signedIn = true),
        )
    }

    /** A shared list as a sync leaves it: clean, with a roster and a close vote from the server. */
    private suspend fun sharedList(kind: String = ListKind.CHECKLIST): String {
        val id = repo.create(TEST_ACCOUNT_ID, "Trip", kind = kind, currency = if (ListKind.isExpenses(kind)) "EUR" else null)
        repo.setCategoryOrder(id, listOf("tent", "food"))
        val row = repo.getById(id)!!
        db.listDao().upsert(
            row.copy(
                dirty = false,
                membersJson = """[{"account_id":"acct-me","email":"me@example.com"},{"account_id":"acct-you","email":"you@example.com"}]""",
                closeVotesJson = """["acct-you"]""",
                closedAt = 5_000L,
            ),
        )
        return id
    }

    @Test
    fun `duplicate into another server account makes a fresh dirty list there with no roster (T-294)`() = runTest {
        addOtherAccounts()
        val sourceId = sharedList()

        val copyId = repo.duplicate(sourceId, "(Copy)", targetAccountId = "work")!!

        val copy = repo.getById(copyId)!!
        val source = repo.getById(sourceId)!!
        assertEquals("work", copy.accountId)
        assertNotEquals(source.serverId, copy.serverId)
        assertEquals("Trip (Copy)", copy.name.value)
        assertEquals(ListKind.CHECKLIST, copy.kind.value)
        assertEquals(listOf("tent", "food"), repo.decodeCategoryOrder(copy.categoryOrder.value))
        assertTrue(copy.dirty)
        assertEquals("device-1", copy.name.updatedBy)
        assertEquals("[]", copy.membersJson)
        assertEquals("[]", copy.closeVotesJson)
    }

    @Test
    fun `duplicate into the local area strips the roster and leaves the shared source untouched (T-294)`() = runTest {
        addOtherAccounts()
        val sourceId = sharedList()
        val before = repo.getById(sourceId)!!

        val copyId = repo.duplicate(sourceId, "(Copy)", targetAccountId = "phone")!!

        val copy = repo.getById(copyId)!!
        assertEquals("phone", copy.accountId)
        assertEquals("[]", copy.membersJson)
        assertEquals("[]", copy.closeVotesJson)
        assertEquals(null, copy.closedAt)
        assertEquals("the source is exactly as it was", before, repo.getById(sourceId))
    }

    @Test
    fun `a ledger is refused into another account, and into the local area, with nothing made (T-294)`() = runTest {
        addOtherAccounts()
        val sourceId = sharedList(ListKind.EXPENSES)

        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.duplicate(sourceId, "(Copy)", targetAccountId = "work") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.duplicate(sourceId, "(Copy)", targetAccountId = "phone") } }

        assertEquals(listOf(sourceId), repo.activeLists().first().map { it.localId })
    }

    @Test
    fun `a ledger copied within its own account keeps its currency (T-294)`() = runTest {
        val sourceId = sharedList(ListKind.EXPENSES)

        val copy = repo.getById(repo.duplicate(sourceId, "(Copy)", targetAccountId = TEST_ACCOUNT_ID)!!)!!

        assertEquals(ListKind.EXPENSES, copy.kind.value)
        assertEquals("EUR", copy.currency.value)
    }

    @Test
    fun `duplicate into an account this phone does not hold makes nothing (T-294)`() = runTest {
        val sourceId = repo.create(TEST_ACCOUNT_ID, "Groceries")

        assertEquals(null, repo.duplicate(sourceId, "(Copy)", targetAccountId = "gone"))
        assertEquals(1, repo.activeLists().first().size)
    }
}
