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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.AuthRepositoryImpl
import org.p23q.shoppinglist.core.AlreadyAddedException
import org.p23q.shoppinglist.core.AppTooOldException
import org.p23q.shoppinglist.core.LocalAreaNotEmptyException
import org.p23q.shoppinglist.core.LoginExpectation
import org.p23q.shoppinglist.core.NotATuppuServerException
import org.p23q.shoppinglist.core.ServerTooOldException
import org.p23q.shoppinglist.core.WrongAccountException
import org.p23q.shoppinglist.core.api.MIN_SERVER_PROTOCOL
import org.p23q.shoppinglist.core.api.PROTOCOL_VERSION
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
        repository = AuthRepositoryImpl(
            accounts.sessions,
            accounts.registry,
            accounts.secrets,
            accounts.secrets,
            db,
            deviceName = "Test device",
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        // A session's 401 or 426 writes its account in the background; that lands first.
        if (::accounts.isInitialized) kotlinx.coroutines.runBlocking { accounts.registry.flush() }
        if (::db.isInitialized) db.close()
    }

    /**
     * An account as 3.1.0 migrated it or a test sets it up: no protocol known for its server, so
     * a login there asks `/app-version` first, as [enqueueLogin] expects by default.
     */
    private suspend fun addAccount(
        serverUrl: String,
        id: String = TEST_ACCOUNT_ID,
        token: String? = "tok-123",
        accountId: String? = "acct-me",
        email: String? = "me@example.com",
    ) = accounts.add(serverUrl, id, token, accountId, email, serverProtocol = null)

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

    private fun noAppPackage(protocol: Int?) = MockResponse().setResponseCode(404).setBody(
        """{"error": "no_app_package", "message": "none"${protocol?.let { ", \"protocol\": $it" } ?: ""}}""",
    )

    /** T-297: a current server without an APK answers 404, and says its protocol there. */
    @Test
    fun `a server without an app package that names its protocol is accepted, and it is stored`() = runTest {
        server.enqueue(noAppPackage(MIN_SERVER_PROTOCOL))
        enqueueLogin(accountId = "acc-1", askFloor = false)

        val id = repository.login(url, "milk@example.com", "hunter2")

        assertEquals(MIN_SERVER_PROTOCOL, accounts.registry.get(id)!!.serverProtocol)
        assertTrue(accounts.registry.get(id)!!.signedIn)
    }

    @Test
    fun `a server without an app package below the floor is refused`() = runTest {
        server.enqueue(noAppPackage(MIN_SERVER_PROTOCOL - 1))

        assertTooOld { repository.login(url, "milk@example.com", "hunter2") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 404 that names no protocol is no Tuppu server, and nothing more is sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "not_found", "message": "nope"}"""))

        assertThrows<NotATuppuServerException> { repository.register(url, "milk@example.com", "hunter2") }
        assertEquals("no registration was attempted", 1, server.requestCount)
    }

    /** T-298: a captive portal or a host that answers every path with a page. */
    @Test
    fun `a 200 that is not the endpoint's JSON is no Tuppu server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Sign in to the Wi-Fi</body></html>"))

        assertThrows<NotATuppuServerException> { repository.login(url, "milk@example.com", "hunter2") }
        assertEquals(1, server.requestCount)
        assertTrue(accounts.registry.snapshot().isEmpty())
    }

    /** T-298: /login would be refused with 426; say so before, with the server's package if any. */
    @Test
    fun `a server above this build's protocol is refused with its download link`() = runTest {
        server.enqueue(appVersion(PROTOCOL_VERSION + 1))

        val e = assertThrows<AppTooOldException> { repository.login(url, "milk@example.com", "hunter2") }

        assertEquals(PROTOCOL_VERSION + 1, e.serverProtocol)
        assertEquals("https://example.com/a.apk", e.downloadUrl)
        assertEquals("no login was attempted", 1, server.requestCount)
        assertTrue(accounts.registry.snapshot().isEmpty())
    }

    @Test
    fun `a server above this build's protocol without an app package is refused with no link`() = runTest {
        server.enqueue(noAppPackage(PROTOCOL_VERSION + 1))

        val e = assertThrows<AppTooOldException> { repository.login(url, "milk@example.com", "hunter2") }

        assertNull(e.downloadUrl)
    }

    @Test
    fun `a stored protocol above this build's is asked again`() = runTest {
        addAccount(url, accountId = "acc-1", token = null)
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverProtocol = PROTOCOL_VERSION + 1) }
        server.enqueue(appVersion(PROTOCOL_VERSION + 1))

        assertThrows<AppTooOldException> { repository.login(url, "milk@example.com", "hunter2") }
        assertEquals("/api/v1/app-version", server.takeRequest().path)
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
        signOut(id)
        val before = server.requestCount

        enqueueLogin(accountId = "acc-1", askFloor = false)
        repository.login(url, "milk@example.com", "hunter2")

        assertEquals(before + 2, server.requestCount)
        repeat(before) { server.takeRequest() }
        assertEquals("/api/v1/login", server.takeRequest().path)
    }

    private suspend fun assertTooOld(block: suspend () -> Unit) {
        assertThrows<ServerTooOldException>(block)
    }

    private suspend inline fun <reified T : Throwable> assertThrows(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    /** As a 401 leaves an account (AccountSessions): no token, signed out, its rows kept. */
    private suspend fun signOut(id: String) {
        accounts.secrets.setToken(id, null)
        accounts.registry.update(id) { it.copy(signedIn = false) }
    }

    /** A login answer for [accountId] with the token `tok-new`, and the settings read. */
    private fun enqueueAnswer(accountId: String, email: String = "milk@example.com") {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-new", "account_id": "$accountId", "email": "$email"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))
    }

    // ---- signing in again, and the other accounts (T-260, T-292, T-300) -----------

    /** The whole round trip of T-260's scenario: signed out by the server, then back in as oneself. */
    @Test
    fun `logging back in as the same account re-activates its row and keeps the mirror (T-260)`() = runTest {
        addAccount(url, accountId = "acc-1")
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        signOut(TEST_ACCOUNT_ID)
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2")

        assertEquals("the same row, not a new one", TEST_ACCOUNT_ID, id)
        assertTrue(accounts.registry.get(id)!!.signedIn)
        assertEquals("tok-acc-1", accounts.secrets.token(id))
        val kept = db.itemDao().get("item-1")
        assertNotNull("the returning user's own edits are still there", kept)
        assertTrue("and still queued to go out", kept!!.dirty)
    }

    /** T-298: the same server typed differently is still the same account, lists and all. */
    @Test
    fun `the same server spelled differently signs the same account back in`() = runTest {
        addAccount(url, accountId = "acc-1", token = null)
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url.uppercase().removeSuffix("/"), "milk@example.com", "hunter2")

        assertEquals(TEST_ACCOUNT_ID, id)
        assertNotNull(db.itemDao().get("item-1"))
    }

    /** T-300: every login keeps the other accounts, server and local, and their lists. */
    @Test
    fun `a login keeps every other account and its lists`() = runTest {
        addAccount(url, accountId = "acc-1")
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
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = false)
        enqueueLogin(accountId = "acc-2", email = "bread@example.com")

        val id = repository.login(url, "bread@example.com", "hunter2")

        assertEquals(setOf(TEST_ACCOUNT_ID, "on-device", id), accounts.registry.snapshot().map { it.id }.toSet())
        assertNotNull(db.listDao().get("list-1"))
        assertNotNull(db.itemDao().get("item-1"))
        assertEquals("tok-123", accounts.secrets.token(TEST_ACCOUNT_ID))
        assertTrue(accounts.registry.get(TEST_ACCOUNT_ID)!!.signedIn)
    }

    /**
     * T-300: adding an account that is here and signed in used to replace its token and reset its
     * cursor before the form said "already added", orphaning the session the token belonged to.
     */
    @Test
    fun `adding an account that is here and signed in is refused before its row changes, and the new session is ended`() = runTest {
        addAccount(url, accountId = "acc-1")
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(syncCursor = 42) }
        val before = accounts.registry.get(TEST_ACCOUNT_ID)
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        enqueueAnswer(accountId = "acc-1")

        assertThrows<AlreadyAddedException> {
            repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.NewAccount)
        }

        assertEquals("tok-123", accounts.secrets.token(TEST_ACCOUNT_ID))
        assertEquals(before, accounts.registry.get(TEST_ACCOUNT_ID))
        assertEquals(listOf(TEST_ACCOUNT_ID), accounts.registry.snapshot().map { it.id })
        server.takeRequest() // app-version
        server.takeRequest() // login
        val revoke = server.takeRequest()
        assertEquals("/api/v1/logout", revoke.path)
        assertEquals("Bearer tok-new", revoke.getHeader("Authorization"))
    }

    @Test
    fun `adding an account that is here but signed out signs its row in again`() = runTest {
        addAccount(url, accountId = "acc-1", token = null)
        signOut(TEST_ACCOUNT_ID)
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.NewAccount)

        assertEquals(TEST_ACCOUNT_ID, id)
        assertTrue(accounts.registry.get(id)!!.signedIn)
    }

    /** T-300: B's credentials in A's "Sign in again" form used to add B and leave A signed out. */
    @Test
    fun `a re-sign-in with another account's credentials is refused and its session ended`() = runTest {
        addAccount(url, accountId = "acc-1", token = null)
        signOut(TEST_ACCOUNT_ID)
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        val before = accounts.registry.get(TEST_ACCOUNT_ID)
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        enqueueAnswer(accountId = "acc-2", email = "bread@example.com")

        assertThrows<WrongAccountException> {
            repository.login(url, "bread@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))
        }

        assertEquals("no row for the other account", listOf(TEST_ACCOUNT_ID), accounts.registry.snapshot().map { it.id })
        assertEquals(before, accounts.registry.get(TEST_ACCOUNT_ID))
        assertNull(accounts.secrets.token(TEST_ACCOUNT_ID))
        assertTrue(db.itemDao().get("item-1")!!.dirty)
        server.takeRequest()
        server.takeRequest()
        val revoke = server.takeRequest()
        assertEquals("/api/v1/logout", revoke.path)
        assertEquals("Bearer tok-new", revoke.getHeader("Authorization"))
    }

    @Test
    fun `a re-sign-in with the account's own credentials signs its row in`() = runTest {
        addAccount(url, accountId = "acc-1", token = null)
        signOut(TEST_ACCOUNT_ID)
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))

        assertEquals(TEST_ACCOUNT_ID, id)
        assertTrue(accounts.registry.get(id)!!.signedIn)
        assertEquals("tok-acc-1", accounts.secrets.token(id))
    }

    /**
     * T-300: a row migrated from 3.1.0 with no recorded owner never matched a sign-in, so every
     * one from its banner added a new row and its lists appeared twice.
     */
    @Test
    fun `a re-sign-in for a row with no recorded owner adopts the account that signs in`() = runTest {
        addAccount(server.url("/typed-last/").toString(), accountId = null, token = null)
        signOut(TEST_ACCOUNT_ID)
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        enqueueLogin(accountId = "acc-1", email = "milk@example.com")

        // The session's real server, corrected in the form.
        val id = repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))

        assertEquals("the same row", TEST_ACCOUNT_ID, id)
        assertEquals(listOf(TEST_ACCOUNT_ID), accounts.registry.snapshot().map { it.id })
        val account = accounts.registry.get(id)!!
        assertEquals("acc-1", account.accountId)
        assertEquals("milk@example.com", account.email)
        assertEquals(url, account.serverUrl)
        assertTrue(account.signedIn)
        assertEquals(account, db.accountDao().all().single())
        assertTrue(db.itemDao().get("item-1")!!.dirty)
    }

    /**
     * T-304: 3.1.0 saved the address before the server answered, so a migrated row can name a
     * server its session was never on. Signed in again at the right one, the row takes it.
     */
    @Test
    fun `a re-sign-in for a row whose server has never answered takes the URL it was signed in with`() = runTest {
        addAccount(server.url("/typed-last/").toString(), accountId = "acc-1", token = null)
        signOut(TEST_ACCOUNT_ID)
        seedList("list-1")
        enqueueLogin(accountId = "acc-1")

        val id = repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))

        assertEquals(TEST_ACCOUNT_ID, id)
        val account = accounts.registry.get(id)!!
        assertEquals(url, account.serverUrl)
        assertEquals(org.p23q.shoppinglist.core.account.serverLabel(url), account.label)
        assertEquals("its protocol is known now", MIN_SERVER_PROTOCOL, account.serverProtocol)
        assertEquals(listOf(TEST_ACCOUNT_ID), db.accountDao().all().map { it.id })
    }

    @Test
    fun `a re-sign-in at another server than the row's known one is refused and its session ended`() = runTest {
        addAccount(server.url("/elsewhere/").toString(), accountId = "acc-1", token = null)
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverProtocol = MIN_SERVER_PROTOCOL) }
        signOut(TEST_ACCOUNT_ID)
        enqueueLogin(accountId = "acc-1")

        assertThrows<WrongAccountException> {
            repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))
        }

        assertEquals(server.url("/elsewhere/").toString(), accounts.registry.get(TEST_ACCOUNT_ID)!!.serverUrl)
        assertFalse(accounts.registry.get(TEST_ACCOUNT_ID)!!.signedIn)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/api/v1/logout", server.takeRequest().path)
    }

    @Test
    fun `a re-sign-in whose account was added again at the right URL is refused, both rows as they were`() = runTest {
        addAccount(server.url("/typed-last/").toString(), accountId = "acc-1", token = null)
        signOut(TEST_ACCOUNT_ID)
        addAccount(url, id = "acc-1-row", accountId = "acc-1")
        enqueueLogin(accountId = "acc-1")

        assertThrows<AlreadyAddedException> {
            repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))
        }

        assertEquals(server.url("/typed-last/").toString(), accounts.registry.get(TEST_ACCOUNT_ID)!!.serverUrl)
        assertEquals("tok-123", accounts.secrets.token("acc-1-row"))
    }

    @Test
    fun `a row with no recorded owner does not adopt an account that has a row already`() = runTest {
        addAccount(url, accountId = null, token = null)
        signOut(TEST_ACCOUNT_ID)
        addAccount(url, id = "acc-1-row", accountId = "acc-1")
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        enqueueAnswer(accountId = "acc-1")

        assertThrows<AlreadyAddedException> {
            repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(TEST_ACCOUNT_ID))
        }

        assertNull(accounts.registry.get(TEST_ACCOUNT_ID)!!.accountId)
        assertEquals("tok-123", accounts.secrets.token("acc-1-row"))
    }

    /** T-300: nothing reads the currency again later but the Account screen, so a failed read stands. */
    @Test
    fun `a failed settings read does not undo the sign-in`() = runTest {
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-acc-2", "account_id": "acc-2", "email": "bread@example.com"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "internal", "message": "boom"}"""))

        val id = repository.login(url, "bread@example.com", "hunter2")

        assertTrue("the sign-in itself stands", accounts.registry.get(id)!!.signedIn)
        assertEquals("tok-acc-2", accounts.secrets.token(id))
        assertNull(accounts.registry.get(id)!!.defaultCurrency)
    }

    /**
     * T-299: a list two accounts on one server share is a row of each, so removing one account
     * takes only its own row, and the other's cursor still describes what the other holds.
     */
    @Test
    fun `removing an account leaves another account's row of a shared list, and its cursor, alone`() = runTest {
        addAccount(url, accountId = "acc-1")
        addAccount(url, id = "same-server", accountId = "acc-2")
        accounts.registry.update("same-server") { it.copy(syncCursor = 42) }
        seedList("mine", serverId = "shared")
        seedList("theirs", owner = "same-server", serverId = "shared")
        seedItem("my-item", "mine", dirty = false, serverId = "shared-item")
        seedItem("their-item", "theirs", dirty = false, serverId = "shared-item")

        repository.removeAccount(TEST_ACCOUNT_ID)

        assertNull(db.listDao().get("mine"))
        assertNull(db.itemDao().get("my-item"))
        assertEquals("theirs", db.listDao().getByServerId("same-server", "shared")!!.localId)
        assertEquals("their-item", db.itemDao().getByServerId("same-server", "shared-item")!!.localId)
        assertEquals(42L, accounts.registry.get("same-server")!!.syncCursor)
        assertEquals(42L, db.accountDao().all().first { it.id == "same-server" }.syncCursor)
    }

    @Test
    fun `removing an account deletes its lists, their items, its token and its row`() = runTest {
        addAccount(url, accountId = "acc-1")
        seedList("list-1")
        seedItem("item-1", "list-1", dirty = true)
        accounts.secrets.lastOpenedListId = "list-1"

        repository.removeAccount(TEST_ACCOUNT_ID)

        assertNull(db.itemDao().get("item-1"))
        assertNull(db.listDao().get("list-1"))
        assertNull(accounts.secrets.token(TEST_ACCOUNT_ID))
        assertNull(accounts.registry.get(TEST_ACCOUNT_ID))
        assertTrue(db.accountDao().all().isEmpty())
        assertNull("a cold start must not reopen a list that is gone", accounts.secrets.lastOpenedListId)
    }

    @Test
    fun `the local area holding a list is not removed, and keeps its lists (T-302)`() = runTest {
        val local = accounts.registry.addLocal()!!
        seedList("hardware", owner = local.id)
        accounts.secrets.lastOpenedListId = "hardware"

        assertThrows<LocalAreaNotEmptyException> { repository.removeAccount(local.id) }

        assertEquals(local, accounts.registry.local())
        assertEquals("hardware", db.listDao().get("hardware")!!.localId)
        assertEquals("hardware", accounts.secrets.lastOpenedListId)
    }

    /** T-302: a sign-in "again" for the local area's row would have made it half a server account. */
    @Test
    fun `a re-sign-in for the local area is refused, its row untouched and the session ended`() = runTest {
        val local = accounts.registry.addLocal()!!
        server.enqueue(appVersion(MIN_SERVER_PROTOCOL))
        enqueueAnswer(accountId = "acc-1")

        assertThrows<WrongAccountException> {
            repository.login(url, "milk@example.com", "hunter2", expect = LoginExpectation.Account(local.id))
        }

        assertEquals(local, accounts.registry.local())
        assertEquals(local, db.accountDao().all().single())
        assertNull(accounts.secrets.token(local.id))
        server.takeRequest() // app-version
        server.takeRequest() // login
        val revoke = server.takeRequest()
        assertEquals("/api/v1/logout", revoke.path)
        assertEquals("Bearer tok-new", revoke.getHeader("Authorization"))
    }

    /** A list with local id [id]; its server id differs from it unless given. */
    private suspend fun seedList(id: String, owner: String = TEST_ACCOUNT_ID, serverId: String = "srv-$id") {
        val now = System.currentTimeMillis()
        db.listDao().upsert(
            ListEntity(
                localId = id,
                serverId = serverId,
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

    /** An item with local id [id] on the list with local id [listId], of that list's account. */
    private suspend fun seedItem(id: String, listId: String, dirty: Boolean, serverId: String = "srv-$id") {
        val now = System.currentTimeMillis()
        db.itemDao().upsert(
            ItemEntity(
                localId = id,
                serverId = serverId,
                accountId = db.listDao().get(listId)!!.accountId,
                listLocalId = listId,
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
