package org.p23q.shoppinglist.core.sync

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.ApiFactory
import org.p23q.shoppinglist.core.account.MapSecretStore
import org.p23q.shoppinglist.core.account.account
import org.p23q.shoppinglist.core.account.testDb
import org.p23q.shoppinglist.core.api.Api
import retrofit2.Retrofit

class InviteCheckerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val db = testDb()
    private val secrets = MapSecretStore()
    private val registry = AccountRegistry(db)
    private val factory = ApiFactory { baseUrl, _, interceptors ->
        val client = OkHttpClient.Builder().apply { interceptors.forEach { addInterceptor(it) } }.build()
        Retrofit.Builder().baseUrl(baseUrl).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(Api::class.java)
    }
    private val syncStatus = SyncStatus()
    private val sessions = AccountSessions(registry, secrets, factory, json, syncStatus)
    private val handed = mutableListOf<List<PendingInvite>>()
    private val checker = InviteChecker(registry, sessions, syncStatus) { handed += it }
    private val servers = mutableMapOf<String, MockWebServer>()

    @Before
    fun setUp() = runBlocking {
        for (id in listOf("a", "b", "out", "old", "tokenless", "failed", "unsynced")) {
            val server = MockWebServer().apply { start() }
            servers[id] = server
            registry.add(account(id, serverUrl = server.url("/").toString(), signedIn = id != "out"))
            if (id != "tokenless") secrets.setToken(id, "tok-$id")
            if (id != "unsynced") syncStatus.account(id).succeeded(at = 1, pending = 0, blocked = 0)
        }
        registry.update("old") { it.copy(outdated = true) }
        syncStatus.account("failed").failed("offline", pending = 0, blocked = 0)
    }

    @After
    fun tearDown() {
        servers.values.forEach { it.shutdown() }
        runBlocking { registry.flush() }
        db.close()
    }

    private fun invites(vararg entries: Pair<String, String>) = MockResponse().setBody(
        """{"invites": [${entries.joinToString(",") { (id, list) ->
            """{"id": "$id", "list_id": "l-$id", "list_name": "$list", "list_kind": "shopping",
               "invited_by_initials": "AB", "expires_at": 5000, "token": "t-$id"}"""
        }}]}""",
    )

    @Test
    fun `every account whose sync succeeded is asked once, and all answers are handed over together`() = runBlocking {
        servers.getValue("a").enqueue(invites("i1" to "Groceries"))
        servers.getValue("b").enqueue(invites("i2" to "Office", "i3" to "Party"))

        checker.check()

        assertEquals(
            listOf(
                PendingInvite("a", "i1", "Groceries", "AB", 5000),
                PendingInvite("b", "i2", "Office", "AB", 5000),
                PendingInvite("b", "i3", "Party", "AB", 5000),
            ),
            handed.single(),
        )
        assertEquals(1, servers.getValue("a").requestCount)
        assertEquals("/api/v1/invites/pending", servers.getValue("a").takeRequest().path)
        assertEquals(1, servers.getValue("b").requestCount)
    }

    @Test
    fun `a signed-out, outdated, token-less, failed or never-synced account is not asked`() = runBlocking {
        servers.getValue("a").enqueue(invites())
        servers.getValue("b").enqueue(invites())

        checker.check()

        for (id in listOf("out", "old", "tokenless", "failed", "unsynced")) {
            assertEquals(id, 0, servers.getValue(id).requestCount)
        }
    }

    @Test
    fun `a failed request leaves that account out and the others still count`() = runBlocking {
        servers.getValue("a").enqueue(MockResponse().setResponseCode(500))
        servers.getValue("b").enqueue(invites("i2" to "Office"))

        checker.check()

        assertEquals(listOf("i2"), handed.single().map { it.id })
    }

    @Test
    fun `with no account answering the notifier is not called`() = runBlocking {
        servers.getValue("a").enqueue(MockResponse().setResponseCode(500))
        servers.getValue("b").enqueue(MockResponse().setResponseCode(503))

        checker.check()

        assertEquals(0, handed.size)
    }
}
