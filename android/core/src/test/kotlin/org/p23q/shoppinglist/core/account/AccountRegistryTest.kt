package org.p23q.shoppinglist.core.account

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRegistryTest {

    private val db = testDb()

    @After
    fun tearDown() = db.close()

    @Test
    fun `nothing is known before load, and everything after`() = runBlocking {
        db.accountDao().insert(account("a"))
        val registry = AccountRegistry(db)

        assertTrue(registry.snapshot().isEmpty())
        registry.load()

        assertEquals(listOf("a"), registry.snapshot().map { it.id })
        assertEquals("server-a", registry.get("a")!!.accountId)
    }

    @Test
    fun `accounts are added after the existing ones and written through`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a"))
        registry.add(account("b"))

        assertEquals(listOf("a", "b"), registry.snapshot().map { it.id })
        assertEquals(listOf(0, 1), registry.snapshot().map { it.sortOrder })
        assertEquals(listOf("a", "b"), db.accountDao().all().map { it.id })
    }

    @Test
    fun `an update is written through`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a"))

        registry.update("a") { it.copy(syncCursor = 17, defaultCurrency = "EUR") }

        assertEquals(17L, registry.get("a")!!.syncCursor)
        assertEquals(17L, db.accountDao().all().single().syncCursor)
        assertEquals("EUR", db.accountDao().all().single().defaultCurrency)
    }

    @Test
    fun `a background update is visible at once and stored soon after`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a"))

        registry.updateInBackground("a") { it.copy(signedIn = false) }
        registry.updateInBackground("a") { it.copy(outdated = true) }

        assertFalse("readers see it before any write", registry.get("a")!!.signedIn)
        registry.flush()
        val stored = db.accountDao().all().single()
        assertFalse(stored.signedIn)
        assertTrue("both changes landed, whatever order the writes ran in", stored.outdated)
    }

    @Test
    fun `an update names one account and leaves the others alone`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a"))
        registry.add(account("b"))

        registry.update("a") { it.copy(signedIn = false) }

        assertFalse(registry.get("a")!!.signedIn)
        assertTrue(registry.get("b")!!.signedIn)
    }

    @Test
    fun `removing an account takes its lists and their items, and nobody else's`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a"))
        registry.add(account("b"))
        db.listDao().upsert(list("la", "a"))
        db.listDao().upsert(list("lb", "b"))
        db.itemDao().upsert(item("ia", "la"))
        db.itemDao().upsert(item("ib", "lb"))

        registry.remove("a")

        assertNull(registry.get("a"))
        assertEquals(listOf("b"), db.accountDao().all().map { it.id })
        assertNull(db.listDao().getById("la"))
        assertNull(db.itemDao().getById("ia"))
        assertEquals("lb", db.listDao().getById("lb")!!.id)
        assertEquals("ib", db.itemDao().getById("ib")!!.id)
    }

    @Test
    fun `a new process starts with no account outdated`() = runBlocking {
        // The flag says the build that got the 426 was too old; this one may be its update.
        db.accountDao().insert(account("a").copy(outdated = true))

        val registry = AccountRegistry(db)
        registry.load()

        assertFalse(registry.get("a")!!.outdated)
        assertFalse(db.accountDao().all().single().outdated)
    }

    @Test
    fun `a list's account must exist`() = runBlocking {
        val failure = runCatching { db.listDao().upsert(list("orphan", "nobody")) }.exceptionOrNull()
        assertTrue("the foreign key refuses it: $failure", failure != null)
    }

    /** T-298: Room's @Upsert turned this conflict into an update that matched nothing, silently. */
    @Test
    fun `a second account with the same server and server id is refused, in the table and the copy`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a", serverUrl = "https://same.example.test/"))

        val refused = runCatching {
            registry.add(account("b", serverUrl = "https://same.example.test/").copy(accountId = "server-a"))
        }.exceptionOrNull()

        assertNotNull("the conflict is surfaced", refused)
        assertEquals(listOf("a"), registry.snapshot().map { it.id })
        assertEquals(listOf("a"), db.accountDao().all().map { it.id })
    }

    @Test
    fun `an update that would collide with another account is refused, and the copy keeps the stored row`() = runBlocking {
        val registry = AccountRegistry(db)
        registry.add(account("a", serverUrl = "https://same.example.test/"))
        registry.add(account("b", serverUrl = "https://same.example.test/"))

        val refused = runCatching { registry.update("b") { it.copy(accountId = "server-a") } }.exceptionOrNull()

        assertNotNull(refused)
        assertEquals("server-b", registry.get("b")!!.accountId)
        assertEquals("server-b", db.accountDao().all().first { it.id == "b" }.accountId)
    }
}
