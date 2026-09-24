package org.p23q.shoppinglist.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ItemEntity
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var serverConfig: ServerConfig
    private lateinit var sessionState: FakeSessionState
    private lateinit var defaultCurrencyState: DefaultCurrencyState
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // Real ARM64-native SQLite via Room's KMP driver, not a Robolectric shadow (see A2's
        // notes in app/build.gradle.kts); Robolectric here only supplies a working Context.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        val tempFile = File.createTempFile("server_config_test", ".preferences_pb")
        tempFile.deleteOnExit()
        serverConfig = ServerConfig(PreferenceDataStoreFactory.create { tempFile })

        sessionState = FakeSessionState()

        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.core.api.SessionEvents()),
            json = json,
        )

        defaultCurrencyState = DefaultCurrencyState(sessionState)
        repository = AuthRepositoryImpl(apiProvider, sessionState, db, defaultCurrencyState)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private suspend fun pointAtServer() {
        serverConfig.setServerUrl(server.url("/").toString())
    }

    @Test
    fun `login stores token, email, and default currency`() = runTest {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-123", "account_id": "acc-1", "email": "milk@example.com"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))

        repository.login("milk@example.com", "hunter2")

        assertEquals("tok-123", sessionState.token)
        assertEquals("milk@example.com", sessionState.accountEmail)
        assertEquals("EUR", sessionState.defaultCurrency)
    }

    @Test
    fun `login also writes the default currency through the in-memory mirror (T-55)`() = runTest {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-123", "account_id": "acc-1", "email": "milk@example.com"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))

        repository.login("milk@example.com", "hunter2")

        assertEquals("EUR", defaultCurrencyState.currency.value)
    }

    @Test
    fun `login stores the account id for collaborator-change detection (T-65)`() = runTest {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-123", "account_id": "acc-1", "email": "milk@example.com"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))

        repository.login("milk@example.com", "hunter2")

        assertEquals("acc-1", sessionState.accountId)
    }

    @Test
    fun `register does not itself store a token`() = runTest {
        pointAtServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"account_id": "acc-1"}"""))

        repository.register("milk@example.com", "hunter2")

        assertNull(sessionState.token)
    }

    @Test
    fun `logout calls the server and clears the session`() = runTest {
        pointAtServer()
        sessionState.token = "tok-123"
        sessionState.accountEmail = "milk@example.com"
        sessionState.accountId = "acc-1"
        seedUnpushedItem()
        server.enqueue(MockResponse().setResponseCode(204))

        repository.logout()

        val recorded = server.takeRequest()
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
        assertNull(sessionState.token)
        // The mirror is not the session's to throw away (T-260): logging out is not a reason to
        // destroy edits that never reached the server. login() decides, once it knows who is back.
        assertNotNull(db.itemDao().getById("item-1"))
        assertEquals("acc-1", sessionState.mirrorAccountId)
    }

    @Test
    fun `logout still wipes local session even if the server is unreachable`() = runTest {
        pointAtServer()
        sessionState.token = "tok-123"
        server.shutdown()

        repository.logout()

        assertNull(sessionState.token)
    }

    /**
     * T-260. clearLocalSession runs on ANY 401 that carried a bearer token — per the Wire Contract
     * that is also an idle-expired session and a password change on another device, which revokes
     * every other session by design. It used to wipe the mirror, so: edit the list offline, change
     * the password on the web, foreground the phone, and the unpushed queue was gone.
     */
    @Test
    fun `a forced logout keeps the unpushed queue (T-260)`() = runTest {
        sessionState.token = "tok-123"
        sessionState.accountId = "acc-1"
        seedUnpushedItem()
        seedSyncedItem()

        repository.clearLocalSession()

        assertNull(sessionState.token)
        val kept = db.itemDao().getById("item-1")
        assertNotNull("the unpushed edit survives a forced logout", kept)
        assertTrue(kept!!.dirty)
        // ...and nothing else does: clearing the session reset the cursor, so the next login
        // re-pulls everything the server still has anyway (T-260).
        assertNull("a synced row is not kept on disk after logout", db.itemDao().getById("item-synced"))
        // And the mirror's owner outlives the session, so login can tell whose data this is.
        assertEquals("acc-1", sessionState.mirrorAccountId)
    }

    /** The whole round trip of the ticket's scenario: forced out, then back in as oneself. */
    @Test
    fun `logging back in as the same account keeps the mirror (T-260)`() = runTest {
        pointAtServer()
        sessionState.accountId = "acc-1"
        seedUnpushedItem()
        repository.clearLocalSession()
        enqueueLogin(accountId = "acc-1")

        repository.login("milk@example.com", "hunter2")

        val kept = db.itemDao().getById("item-1")
        assertNotNull("the returning user's own edits are still there", kept)
        assertTrue("and still queued to go out", kept!!.dirty)
    }

    /**
     * The privacy property the wipe existed for, now enforced where it can actually be decided
     * (T-260): a different account must never see the previous account's lists.
     */
    @Test
    fun `logging in as a different account wipes the mirror (T-260)`() = runTest {
        pointAtServer()
        sessionState.mirrorAccountId = "acc-1"
        seedUnpushedItem()
        enqueueLogin(accountId = "acc-2")

        repository.login("bread@example.com", "hunter2")

        assertNull("another account must not see the previous one's lists", db.itemDao().getById("item-1"))
        assertEquals("acc-2", sessionState.mirrorAccountId)
    }

    @Test
    fun `an unrecorded mirror owner counts as a different account (T-260)`() = runTest {
        pointAtServer()
        // No owner recorded — a pre-T-65 session, say, which cannot prove the mirror is this user's.
        assertNull(sessionState.mirrorAccountId)
        seedUnpushedItem()
        enqueueLogin(accountId = "acc-1")

        repository.login("milk@example.com", "hunter2")

        assertNull("privacy wins the tie when whose data it is cannot be established", db.itemDao().getById("item-1"))
    }

    private fun enqueueLogin(accountId: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"token": "tok-123", "account_id": "$accountId", "email": "milk@example.com"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))
    }

    /** A row the server can send again: dropped on logout, unlike the unpushed one. */
    private suspend fun seedSyncedItem() {
        val now = System.currentTimeMillis()
        db.itemDao().upsert(
            ItemEntity(
                id = "item-synced",
                listId = "list-1",
                createdAt = now,
                name = "Bread".toLww("dev", now),
                category = null.toLwwOptional("dev", now),
                stores = "[]".toLww("dev", now),
                quantity = null.toLwwOptional("dev", now),
                price = null.toLwwOptional("dev", now),
                note = null.toLwwOptional("dev", now),
                status = "todo".toLww("dev", now),
                deleted = false.toLww("dev", now),
                dirty = false,
            ),
        )
    }

    private suspend fun seedUnpushedItem() {
        val now = System.currentTimeMillis()
        db.itemDao().upsert(
            ItemEntity(
                id = "item-1",
                listId = "list-1",
                createdAt = now,
                name = "Milk".toLww("dev", now),
                category = null.toLwwOptional("dev", now),
                stores = "[]".toLww("dev", now),
                quantity = null.toLwwOptional("dev", now),
                price = null.toLwwOptional("dev", now),
                note = null.toLwwOptional("dev", now),
                status = "todo".toLww("dev", now),
                deleted = false.toLww("dev", now),
                dirty = true,
            ),
        )
    }
}
