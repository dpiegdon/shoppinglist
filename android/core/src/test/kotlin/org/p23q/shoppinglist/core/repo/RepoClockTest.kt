package org.p23q.shoppinglist.core.repo

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.core.Clock
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.account.account
import org.p23q.shoppinglist.core.account.testDb
import org.p23q.shoppinglist.core.sync.SyncTrigger

/** The repositories stamp their field clocks and creation times from the [Clock] they are given. */
class RepoClockTest {
    private val db = testDb()
    private var now = 1_000L
    private val clock = Clock { now }
    private val trigger = object : SyncTrigger {
        override fun scheduleAfterEdit() {}

        override fun scheduleImmediate() {}
    }
    private val lists = ListsRepo(db, DeviceIdProvider { "dev" }, trigger, clock)
    private val items = ItemsRepo(db, DeviceIdProvider { "dev" }, trigger, clock)

    @After
    fun tearDown() = db.close()

    @Test
    fun `a new list and item carry the clock's time`() = runBlocking {
        db.accountDao().insert(account("a"))
        val listId = lists.create("a", "Groceries")
        val itemId = items.createItem(listId, "Milk")

        val list = lists.getById(listId)!!
        assertEquals(1_000L, list.createdAt)
        assertEquals(1_000L, list.name.updatedAt)
        val item = items.getById(itemId)!!
        assertEquals(1_000L, item.createdAt)
        assertEquals(1_000L, item.status.updatedAt)
    }

    @Test
    fun `an edit stamps the clock's time on the field it changes`() = runBlocking {
        db.accountDao().insert(account("a"))
        val listId = lists.create("a", "Groceries")
        val itemId = items.createItem(listId, "Milk")

        now = 2_000L
        lists.rename(listId, "Food")
        items.setNote(itemId, "the ripe ones")

        assertEquals(2_000L, lists.getById(listId)!!.name.updatedAt)
        assertEquals(1_000L, lists.getById(listId)!!.createdAt)
        assertEquals(2_000L, items.getById(itemId)!!.note.updatedAt)
        assertEquals(1_000L, items.getById(itemId)!!.name.updatedAt)
    }
}
