package org.p23q.shoppinglist.core.account

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.LoginRequest
import org.p23q.shoppinglist.core.sync.SyncStatus
import retrofit2.Retrofit

class AccountSessionsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val db = testDb()
    private val secrets = MapSecretStore()
    private val registry = AccountRegistry(db)
    private var built = 0
    private val factory = ApiFactory { baseUrl, _, interceptors ->
        built++
        val client = OkHttpClient.Builder().apply { interceptors.forEach { addInterceptor(it) } }.build()
        Retrofit.Builder().baseUrl(baseUrl).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(Api::class.java)
    }
    private val sessions = AccountSessions(registry, secrets, factory, json, SyncStatus())
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    @Before
    fun setUp() = runBlocking {
        a = MockWebServer().apply { start() }
        b = MockWebServer().apply { start() }
        registry.add(account("a", serverUrl = a.url("/").toString()))
        registry.add(account("b", serverUrl = b.url("/").toString()))
        secrets.setToken("a", "tok-a")
        secrets.setToken("b", "tok-b")
    }

    @After
    fun tearDown() {
        a.shutdown()
        b.shutdown()
        db.close()
    }

    @Test
    fun `a session is built once per account and cached`() {
        val first = sessions.get("a")
        assertSame(first, sessions.get("a"))
        assertNotSame(first, sessions.get("b"))
        assertEquals(2, built)
    }

    @Test
    fun `a session is rebuilt when its server or certificate opt-in changes, and forgotten when dropped`() = runBlocking {
        val first = sessions.get("a")
        registry.update("a") { it.copy(allowSelfSignedCerts = true) }
        val second = sessions.get("a")
        assertNotSame(first, second)
        sessions.drop("a")
        assertNotSame(second, sessions.get("a"))
    }

    @Test
    fun `each session carries its own account's token`() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))
        b.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))

        sessions.get("a").api.lists()
        sessions.get("b").api.lists()

        assertEquals("Bearer tok-a", a.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-b", b.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 401 signs out only its own account and names it on forcedLogout`() = runBlocking {
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { sessions.forcedLogout.first() } }
        a.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "revoked"}"""))

        runCatching { sessions.get("a").api.lists() }

        assertEquals("a", event.await())
        assertFalse(registry.get("a")!!.signedIn)
        assertTrue(registry.get("b")!!.signedIn)
        assertNull("the dead token is not sent again (T-298)", secrets.token("a"))
        assertEquals("tok-b", secrets.token("b"))
        registry.flush()
    }

    /** T-298: signed in again while a request with the old token was still out. */
    @Test
    fun `a 401 to a token the account no longer holds changes nothing`() = runBlocking {
        a.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                secrets.setToken("a", "tok-new")
                return MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "revoked"}""")
            }
        }
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(500) { sessions.forcedLogout.first() } }

        runCatching { sessions.get("a").api.lists() }

        assertNull("no forced logout", event.await())
        assertTrue(registry.get("a")!!.signedIn)
        assertEquals("tok-new", secrets.token("a"))
    }

    @Test
    fun `a 426 marks only its own account, and the app is blocked only once all are`() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(426).setBody("""{"error": "client_outdated", "message": "old"}"""))
        runCatching { sessions.get("a").api.lists() }

        assertTrue(registry.get("a")!!.outdated)
        assertFalse(registry.get("b")!!.outdated)
        assertFalse(sessions.isUpdateRequired())

        b.enqueue(MockResponse().setResponseCode(426).setBody("""{"error": "client_outdated", "message": "old"}"""))
        runCatching { sessions.get("b").api.lists() }

        assertTrue(sessions.isUpdateRequired())
        assertTrue(sessions.updateRequired.first())
    }

    @Test
    fun `a 426 on a sign-in attempt is the login screen's error and does not block the app (T-298)`() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(426).setBody("""{"error": "client_outdated", "message": "old"}"""))

        val error = runCatching {
            sessions.unbound(a.url("/").toString(), false).login(LoginRequest("x@example.com", "pw", "dev", "android"))
        }.exceptionOrNull()

        assertEquals("client_outdated", (error as ApiException).code)
        assertFalse(sessions.isUpdateRequired())
        assertFalse(sessions.updateRequired.first())
        assertTrue(registry.snapshot().none { it.outdated })
    }

    @Test
    fun `an unbound client carries no token`() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(200).setBody("""{"allow_registration": true}"""))

        sessions.unbound(a.url("/").toString(), false).registrationStatus()

        assertEquals(null, a.takeRequest().getHeader("Authorization"))
    }
}
