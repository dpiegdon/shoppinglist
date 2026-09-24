package org.p23q.shoppinglist.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.AuthRepositoryImpl
import org.p23q.shoppinglist.core.DefaultCurrencyState
import org.p23q.shoppinglist.core.ServerTooOldException
import org.p23q.shoppinglist.core.api.MIN_SERVER_PROTOCOL
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.db.ListEntity
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private lateinit var defaultCurrencyState: DefaultCurrencyState
    private lateinit var repository: AuthRepository
    private lateinit var url: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        url = server.url("/").toString()

        // Real native SQLite via Room's KMP driver, not a Robolectric shadow (see A2's notes in
        // app/build.gradle.kts); Robolectric here only supplies a working Context.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        accounts = TestAccounts(db)
        defaultCurrencyState = DefaultCurrencyState(accounts.currentAccount)
        repository = AuthRepositoryImpl(
            accounts.sessions,
            accounts.registry,
            accounts.secrets,
            accounts.secrets,
            db,
            defaultCurrencyState,
            deviceName = "Test device",
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private fun appVersion(protocol: Int?) = MockResponse().setResponseCode(200).setBody(
        """{"version": "1.0.0", "download_url": "https://example.com/a.apk"${protocol?.let { ", \"protocol\": $it" } ?: ""}}""",
    )

    /** The floor check (unless the server's protocol is already known), the login and the settings read. */
    private fun enqueueLogin(accountId: String, email: String = "milk@example.com", askFloor: Boolean = true) {
        if (askFloor) server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-$accountId", "account_id": "$accountId", "email": "$email"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))
    }

    @Test
    fun `login creates a signed-in account with its token, email, server id and currency`() = runTest {
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2")

        val account = accounts.registry.get(id)!!
        assertEquals(url, account.serverUrl)
        assertEquals("acc-1", account.accountId)
        assertEquals("milk@example.com", account.email)
        assertEquals("EUR", account.defaultCurrency)
        assertTrue(account.signedIn)
        assertEquals(MIN_SERVER_PROTOCOL, account.serverProtocol)
        assertEquals("tok-acc-1", accounts.secrets.token(id))
        // Written through, not only held in memory.
        assertEquals(account, db.accountDao().all().single())
        // And the one account is what the single-account screens see.
        assertEquals("tok-acc-1", accounts.currentAccount.token)
        assertEquals("acc-1", accounts.currentAccount.accountId)
    }

    @Test
    fun `login also writes the default currency through the in-memory mirror (T-55)`() = runTest {
        enqueueLogin(accountId = "acc-1")

        repository.login(url, "milk@example.com", "hunter2")

        assertEquals("EUR", defaultCurrencyState.currency.value)
    }

    @Test
    fun `the settings read after login carries the new account's token`() = runTest {
        enqueueLogin(accountId = "acc-1")

        repository.login(url, "milk@example.com", "hunter2")

        server.takeRequest() // app-version
        assertNull("login itself carries no token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-acc-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `register does not itself store a token or an account`() = runTest {
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"account_id": "acc-1"}"""))

        repository.register(url, "milk@example.com", "hunter2")

        assertTrue(accounts.registry.snapshot().isEmpty())
        assertTrue(accounts.secrets.tokens.isEmpty())
    }

    // ---- the protocol floor (T-291) -----------------------------------------------

    @Test
    fun `a server that names no protocol is refused before anything else is sent to it`() = runTest {
        server.enqueue(appVersion(protocol = null))

        assertTooOld { repository.login(url, "milk@example.com", "hunter2") }

        assertEquals("/api/v1/app-version", server.takeRequest().path)
        assertEquals("nothing but the question went out", 1, server.requestCount)
        assertTrue("and nothing was stored", accounts.registry.snapshot().isEmpty())
    }

    @Test
    fun `a server below the floor is refused`() = runTest {
        server.enqueue(appVersion(protocol = MIN_SERVER_PROTOCOL - 1))

        assertTooOld { repository.login(url, "milk@example.com", "hunter2") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server from before app-version existed is refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "not_found", "message": "nope"}"""))

        assertTooOld { repository.register(url, "milk@example.com", "hunter2") }
        assertEquals("no registration was attempted", 1, server.requestCount)
    }

    @Test
    fun `a server at the floor is accepted`() = runTest {
        enqueueLogin(accountId = "acc-1")

        repository.login(url, "milk@example.com", "hunter2")

        assertEquals("/api/v1/app-version", server.takeRequest().path)
        assertEquals("/api/v1/login", server.takeRequest().path)
    }

    @Test
    fun `a server whose protocol is known is not asked again`() = runTest {
        enqueueLogin(accountId = "acc-1")
        val id = repository.login(url, "milk@example.com", "hunter2")
        repository.clearLocalSession(id)
        val before = server.requestCount

        enqueueLogin(accountId = "acc-1", askFloor = false)
        repository.login(url, "milk@example.com", "hunter2")

        assertEquals(before + 2, server.requestCount)
        repeat(before) { server.takeRequest() }
        assertEquals("/api/v1/login", server.takeRequest().path)
    }

    private suspend fun assertTooOld(block: suspend () -> Unit) {
        try {
            block()
            fail("expected ServerTooOldException")
        } catch (_: ServerTooOldException) {
            // expected
        }
    }

    // ---- logout and the mirror (T-257, T-260) --------------------------------------

    @Test
    fun `logout calls the server with the account's token and signs it out, keeping unpushed rows`() = runTest {
        accounts.add(url, accountId = "acc-1")
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        server.enqueue(MockResponse().setResponseCode(204))

        repository.logout(TEST_ACCOUNT_ID)

        val recorded = server.takeRequest()
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
        assertNull(accounts.secrets.token(TEST_ACCOUNT_ID))
        val account = accounts.registry.get(TEST_ACCOUNT_ID)!!
        assertFalse(account.signedIn)
        // The row stays and still says whose lists these are, for the next login to judge (T-260).
        assertEquals("acc-1", account.accountId)
        assertNotNull(db.itemDao().getById("item-1"))
        assertNull(accounts.currentAccount.token)
    }

    @Test
    fun `logout still signs out if the server is unreachable`() = runTest {
        accounts.add(url)
        server.shutdown()

        repository.logout(TEST_ACCOUNT_ID)

        assertNull(accounts.secrets.token(TEST_ACCOUNT_ID))
        assertFalse(accounts.registry.get(TEST_ACCOUNT_ID)!!.signedIn)
    }

    /**
     * T-260. clearLocalSession runs on ANY 401 that carried a bearer token — per the Wire Contract
     * that is also an idle-expired session and a password change on another device, which revokes
     * every other session by design. It used to wipe the mirror, so: edit the list offline, change
     * the password on the web, foreground the phone, and the unpushed queue was gone.
     */
    @Test
    fun `a forced logout keeps the unpushed queue and drops what the server can resend (T-260)`() = runTest {
        accounts.add(url, accountId = "acc-1")
        seedList("list-1")
        seedList("list-clean")
        seedItem("item-1", "list-1", dirty = true)
        seedItem("item-synced", "list-1", dirty = false)
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(syncCursor = 42) }

        repository.clearLocalSession(TEST_ACCOUNT_ID)

        val kept = db.itemDao().getById("item-1")
        assertNotNull("the unpushed edit survives a forced logout", kept)
        assertTrue(kept!!.dirty)
        assertNull("a synced row is not kept on disk after logout", db.itemDao().getById("item-synced"))
        assertNull("nor a synced list with nothing unpushed in it", db.listDao().getById("list-clean"))
        assertNotNull("but the list the unpushed item is on stays with it", db.listDao().getById("list-1"))
        assertEquals("the next login re-pulls from 0", 0L, accounts.registry.get(TEST_ACCOUNT_ID)!!.syncCursor)
    }

    @Test
    fun `a forced logout of one account leaves every other account's lists alone`() = runTest {
        accounts.add(url, accountId = "acc-1")
        accounts.add(server.url("/other/").toString(), id = "other", accountId = "acc-9")
        seedList("mine", owner = TEST_ACCOUNT_ID)
        seedList("theirs", owner = "other")
        seedItem("their-synced-item", "theirs", dirty = false)

        repository.clearLocalSession(TEST_ACCOUNT_ID)

        assertNull(db.listDao().getById("mine"))
        assertNotNull(db.listDao().getById("theirs"))
        assertNotNull(db.itemDao().getById("their-synced-item"))
        assertTrue(accounts.registry.get("other")!!.signedIn)
        assertEquals("tok-123", accounts.secrets.token("other"))
    }

    /** The whole round trip of the ticket's scenario: forced out, then back in as oneself. */
    @Test
    fun `logging back in as the same account re-activates its row and keeps the mirror (T-260)`() = runTest {
        accounts.add(url, accountId = "acc-1")
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        repository.clearLocalSession(TEST_ACCOUNT_ID)
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2")

        assertEquals("the same row, not a new one", TEST_ACCOUNT_ID, id)
        assertTrue(accounts.registry.get(id)!!.signedIn)
        assertEquals("tok-acc-1", accounts.secrets.token(id))
        val kept = db.itemDao().getById("item-1")
        assertNotNull("the returning user's own edits are still there", kept)
        assertTrue("and still queued to go out", kept!!.dirty)
    }

    /**
     * The privacy property the wipe existed for, enforced where it can actually be decided (T-260):
     * a different account must never see the previous account's lists. A login removes every
     * other server account itself.
     */
    @Test
    fun `signing in as a different account wipes the other mirror (T-260)`() = runTest {
        accounts.add(url, accountId = "acc-1", token = null)
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        enqueueLogin(accountId = "acc-2", email = "bread@example.com")

        val id = repository.login(url, "bread@example.com", "hunter2")

        assertNotEquals(TEST_ACCOUNT_ID, id)
        assertNull("another account must not see the previous one's lists", db.itemDao().getById("item-1"))
        assertNull(db.listDao().getById("list-1"))
        assertEquals(listOf(id), accounts.registry.snapshot().map { it.id })
        assertEquals(listOf(id), db.accountDao().all().map { it.id })
    }

    @Test
    fun `an unrecorded mirror owner counts as a different account (T-260)`() = runTest {
        // No owner recorded — a pre-T-65 session, say, which cannot prove the mirror is this user's.
        accounts.add(url, accountId = null, token = null)
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        enqueueLogin(accountId = "acc-1")

        repository.login(url, "milk@example.com", "hunter2")

        assertNull("privacy wins the tie when whose data it is cannot be established", db.itemDao().getById("item-1"))
    }

    /**
     * T-298: the other accounts went only after login had returned, so a failed settings read left
     * the previous person's lists on the device beside a signed-in new account that the worker
     * would sync.
     */
    @Test
    fun `a failed settings read still leaves only the account that signed in (T-298)`() = runTest {
        accounts.add(url, accountId = "acc-1")
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-acc-2", "account_id": "acc-2", "email": "bread@example.com"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "internal", "message": "boom"}"""))

        val id = repository.login(url, "bread@example.com", "hunter2")

        assertEquals(listOf(id), accounts.registry.snapshot().map { it.id })
        assertEquals(listOf(id), db.accountDao().all().map { it.id })
        assertNull(db.listDao().getById("list-1"))
        assertNull(db.itemDao().getById("item-1"))
        assertTrue("the sign-in itself stands", accounts.registry.get(id)!!.signedIn)
        assertEquals("tok-acc-2", accounts.secrets.token(id))
    }

    @Test
    fun `a login never removes a local account`() = runTest {
        accounts.registry.add(
            AccountEntity(
                id = "on-device",
                kind = AccountEntity.KIND_LOCAL,
                serverUrl = null,
                accountId = null,
                email = null,
                label = "This phone",
                signedIn = false,
            ),
        )
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2")

        assertEquals(setOf("on-device", id), accounts.registry.snapshot().map { it.id }.toSet())
    }

    @Test
    fun `a login asked to keep the other accounts keeps them`() = runTest {
        accounts.add(url, accountId = "acc-1")
        seedList("list-1")
        enqueueLogin(accountId = "acc-2")

        val id = repository.login(url, "bread@example.com", "hunter2", keepOtherAccounts = true)

        assertEquals(setOf(TEST_ACCOUNT_ID, id), accounts.registry.snapshot().map { it.id }.toSet())
        assertNotNull(db.listDao().getById("list-1"))
    }

    /**
     * T-298: a list both accounts can see is one row, owned by whichever pulled it first; removing
     * that account deletes the row under the other, whose cursor has moved past it already.
     */
    @Test
    fun `removing an account makes every other account on its server pull from 0 again`() = runTest {
        accounts.add(url, accountId = "acc-1")
        accounts.add(url, id = "same-server", accountId = "acc-2")
        accounts.add(server.url("/other/").toString(), id = "other-server", accountId = "acc-3")
        listOf("same-server", "other-server").forEach { id -> accounts.registry.update(id) { it.copy(syncCursor = 42) } }

        repository.removeAccount(TEST_ACCOUNT_ID)

        assertEquals(0L, accounts.registry.get("same-server")!!.syncCursor)
        assertEquals(0L, db.accountDao().all().first { it.id == "same-server" }.syncCursor)
        assertEquals("another server's cursor stays", 42L, accounts.registry.get("other-server")!!.syncCursor)
    }

    @Test
    fun `removing an account deletes its lists, their items, its token and its row`() = runTest {
        accounts.add(url, accountId = "acc-1")
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        accounts.secrets.lastOpenedListId = "list-1"

        repository.removeAccount(TEST_ACCOUNT_ID)

        assertNull(db.itemDao().getById("item-1"))
        assertNull(db.listDao().getById("list-1"))
        assertNull(accounts.secrets.token(TEST_ACCOUNT_ID))
        assertNull(accounts.registry.get(TEST_ACCOUNT_ID))
        assertTrue(db.accountDao().all().isEmpty())
        assertNull("a cold start must not reopen a list that is gone", accounts.secrets.lastOpenedListId)
    }

    private suspend fun seedList(id: String, owner: String = TEST_ACCOUNT_ID) {
        val now = System.currentTimeMillis()
        db.listDao().upsert(
            ListEntity(
                id = id,
                accountId = owner,
                createdAt = now,
                name = id.toLww("dev", now),
                categoryOrder = "[]".toLww("dev", now),
                notes = null.toLwwOptional("dev", now),
                kind = "shopping".toLww("dev", now),
                deleted = false.toLww("dev", now),
                dirty = false,
            ),
        )
    }

    private suspend fun seedItem(id: String, listId: String, dirty: Boolean) {
        val now = System.currentTimeMillis()
        db.itemDao().upsert(
            ItemEntity(
                id = id,
                listId = listId,
                createdAt = now,
                name = id.toLww("dev", now),
                category = null.toLwwOptional("dev", now),
                stores = "[]".toLww("dev", now),
                quantity = null.toLwwOptional("dev", now),
                price = null.toLwwOptional("dev", now),
                note = null.toLwwOptional("dev", now),
                status = "todo".toLww("dev", now),
                deleted = false.toLww("dev", now),
                dirty = dirty,
            ),
        )
    }
}
