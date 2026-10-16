package org.p23q.shoppinglist.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.core.api.AdminPasswordRequest
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.AppJson
import org.p23q.shoppinglist.core.api.ChangeEmailRequest
import org.p23q.shoppinglist.core.api.ChangePasswordRequest
import org.p23q.shoppinglist.core.api.CreateInviteRequest
import org.p23q.shoppinglist.core.api.DeleteAccountRequest
import org.p23q.shoppinglist.core.api.LoginRequest
import org.p23q.shoppinglist.core.api.RedeemInviteRequest
import org.p23q.shoppinglist.core.api.RegisterRequest
import org.p23q.shoppinglist.core.api.ServerSettingsUpdate
import org.p23q.shoppinglist.core.api.SyncRequest
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.api.UpdateSettingsRequest
import org.p23q.shoppinglist.core.api.createApi
import java.io.File

/**
 * Every endpoint's request as it goes out on the wire: method, path, headers and body. The
 * transcript in src/test/resources was recorded from the Retrofit client this one replaced, so a
 * difference here is a change on the wire.
 */
class WireTranscriptTest {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("{}")
        }
        start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val engines = OkHttpEngineFactory()

    /** An account's client, signed in with "tok-1". */
    private fun api(): Api = createApi(
        server.url("/base/").toString(),
        engines.create(false),
        AppJson,
        TokenProvider { "tok-1" },
        userAgent = engines.userAgent,
    )

    /** The client that belongs to no account, the only one that sends /logout. */
    private fun unbound(): Api =
        createApi(server.url("/base/").toString(), engines.create(false), AppJson, userAgent = engines.userAgent)

    private fun transcript(): String {
        val api = api()
        val unbound = unbound()
        val calls: List<suspend () -> Unit> = listOf(
            { api.register(RegisterRequest("a@example.com", "pw")) },
            { api.login(LoginRequest("a@example.com", "pw", "Phone", "android")) },
            { api.registrationStatus() },
            { api.castCloseVote("list 1") },
            { api.withdrawCloseVote("list-1") },
            { unbound.logout("Bearer other") },
            { unbound.appVersion() },
            { api.appVersion() },
            { api.changePassword(ChangePasswordRequest("old", "new")) },
            { api.changeEmail(ChangeEmailRequest("pw", "b@example.com")) },
            { api.sessions() },
            { api.revokeSession("s-1") },
            { api.deleteAccount(DeleteAccountRequest("pw")) },
            { api.getSettings() },
            { api.updateSettings(UpdateSettingsRequest(initials = "AB")) },
            { api.lists() },
            { api.members("list-1") },
            { api.leaveList("list-1") },
            { api.createInvite("list-1", CreateInviteRequest("c@example.com")) },
            { api.revokeInvite("inv-1") },
            { api.redeemInvite(RedeemInviteRequest("t0k")) },
            { api.pendingInvites() },
            { api.sync(SyncRequest(cursor = 7, deviceId = "dev-1", fullLists = listOf("list-1"))) },
            { api.adminUsers() },
            { api.adminGetServerSettings() },
            { api.adminSetServerSettings(ServerSettingsUpdate(allowRegistration = true, message = "Hällo")) },
            { api.adminResetPassword("u-1", AdminPasswordRequest("pw")) },
            { api.adminDeleteUser("u-1", AdminPasswordRequest("pw")) },
        )
        val out = StringBuilder()
        runBlocking {
            for (call in calls) {
                try {
                    call()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // "{}" is not every endpoint's answer; only the request matters here.
                }
                val request = server.takeRequest()
                out.append(request.method).append(' ').append(request.path).append('\n')
                request.headers.names().sorted().forEach { name ->
                    val value = if (name.equals("Host", ignoreCase = true)) "localhost" else request.headers.values(name).joinToString(", ")
                    out.append("  ").append(name.lowercase()).append(": ").append(value).append('\n')
                }
                val body = request.body.readUtf8()
                if (body.isNotEmpty()) out.append("  ").append(body).append('\n')
            }
        }
        return out.toString()
    }

    @Test
    fun `every endpoint's request is the one the server has always got`() {
        val actual = transcript()
        val expected = File("src/test/resources/wire-transcript.txt").readText()
        // Beside it for a diff, when they differ.
        if (actual != expected) File("build/wire-transcript-actual.txt").writeText(actual)
        assertEquals(expected, actual)
    }
}
