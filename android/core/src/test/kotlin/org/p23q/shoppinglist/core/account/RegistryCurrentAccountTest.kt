package org.p23q.shoppinglist.core.account

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.p23q.shoppinglist.core.db.AccountEntity

/** The single-account screens' view of the accounts (T-291), and its rules (T-298). */
class RegistryCurrentAccountTest {

    private val db = testDb()
    private val registry = AccountRegistry(db)
    private val secrets = MapSecretStore()
    private val current = RegistryCurrentAccount(registry, secrets, secrets)

    @After
    fun tearDown() = runBlocking {
        registry.flush()
        db.close()
    }

    private fun local(id: String) = AccountEntity(
        id = id,
        kind = AccountEntity.KIND_LOCAL,
        serverUrl = null,
        accountId = null,
        email = null,
        label = id,
        signedIn = true,
    )

    @Test
    fun `the current account is the first server account, not a local one ahead of it`() = runBlocking {
        registry.add(local("on-device"))
        registry.add(account("a"))
        registry.add(account("b"))

        assertEquals("a", current.localId)
        assertEquals("https://a.example.test/", current.serverUrl)
        assertEquals("server-a", current.accountId)
    }

    @Test
    fun `there is no token while the account is signed out, whatever the store still holds`() = runBlocking {
        registry.add(account("a"))
        secrets.setToken("a", "tok-a")
        assertEquals("tok-a", current.token)

        registry.update("a") { it.copy(signedIn = false) }

        assertNull(current.token)
    }

    @Test
    fun `with no account every read says nothing and a write is dropped`() = runBlocking {
        registry.load()

        current.defaultCurrency = "EUR"
        current.accountEmail = "me@example.com"
        current.allowSelfSignedCerts = true
        registry.flush()

        assertNull(current.localId)
        assertNull(current.token)
        assertNull(current.defaultCurrency)
        assertFalse(current.allowSelfSignedCerts)
        assertTrue("nothing was stored", db.accountDao().all().isEmpty())
    }

    @Test
    fun `a write goes to the current account, at once and to the table`() = runBlocking {
        registry.add(account("a"))
        registry.add(account("b"))

        current.defaultCurrency = "EUR"
        assertEquals("EUR", registry.get("a")!!.defaultCurrency)
        assertNull(registry.get("b")!!.defaultCurrency)
        assertEquals("EUR", current.defaultCurrencyChanges.first())
        registry.flush()

        assertEquals("EUR", db.accountDao().get("a")!!.defaultCurrency)
    }
}
