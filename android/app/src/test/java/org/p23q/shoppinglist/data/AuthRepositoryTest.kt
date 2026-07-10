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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.toLww
import org.p23q.shoppinglist.data.db.toLwwOptional
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var serverConfig: ServerConfig
    private lateinit var sessionState: FakeSessionState
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
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )

        repository = AuthRepositoryImpl(apiProvider, sessionState, db)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
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
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR"}"""))

        repository.login("milk@example.com", "hunter2")

        assertEquals("tok-123", sessionState.token)
        assertEquals("milk@example.com", sessionState.accountEmail)
        assertEquals("EUR", sessionState.defaultCurrency)
    }

    @Test
    fun `register does not itself store a token`() = runTest {
        pointAtServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"account_id": "acc-1"}"""))

        repository.register("milk@example.com", "hunter2")

        assertNull(sessionState.token)
    }

    @Test
    fun `logout calls the server, clears the session, and wipes local data`() = runTest {
        pointAtServer()
        sessionState.token = "tok-123"
        sessionState.accountEmail = "milk@example.com"
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
        server.enqueue(MockResponse().setResponseCode(204))

        repository.logout()

        val recorded = server.takeRequest()
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
        assertNull(sessionState.token)
        assertNull(db.itemDao().getById("item-1"))
    }

    @Test
    fun `logout still wipes local session even if the server is unreachable`() = runTest {
        pointAtServer()
        sessionState.token = "tok-123"
        server.shutdown()

        repository.logout()

        assertNull(sessionState.token)
    }
}
