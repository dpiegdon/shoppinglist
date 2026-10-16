package org.p23q.shoppinglist.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/**
 * The HTTP engine requests go out on, which only the platform can build: on Android OkHttp, with
 * the debug-only certificate opt-in; on iOS Darwin's NSURLSession.
 */
interface HttpEngineFactory {
    /**
     * A new engine, for one client. [allowSelfSignedCerts] is the account's opt-in to trust any
     * certificate, which a release build ignores. Clients are never closed, and an engine holds
     * on to every client built on it, so an engine must not be handed out twice.
     */
    fun create(allowSelfSignedCerts: Boolean): HttpClientEngine

    /** The User-Agent every request carries; null leaves Ktor's own. */
    val userAgent: String? get() = null
}

/**
 * A client for the server at [baseUrl] (an http(s) URL ending in '/'), on [engine].
 *
 * Every request carries the protocol header ([ProtocolHeader]); with [tokens], also its bearer
 * token ([BearerAuth]). A non-2xx answer throws the [ApiException] its error envelope describes
 * and is reported to [events] ([ApiErrors]). Redirects are the engine's to follow: OkHttp does,
 * as it always has.
 */
fun createApi(
    baseUrl: String,
    engine: HttpClientEngine,
    json: Json,
    tokens: TokenProvider? = null,
    events: ApiEvents = ApiEvents.NONE,
    userAgent: String? = null,
): Api {
    require(baseUrl.startsWith("http://", ignoreCase = true) || baseUrl.startsWith("https://", ignoreCase = true)) {
        "Expected an http or https URL: $baseUrl"
    }
    require(baseUrl.endsWith("/")) { "The server URL must end in '/': $baseUrl" }
    val client = HttpClient(engine) {
        // Only what is asked for goes out: no Accept or Accept-Charset of Ktor's own. Bodies are
        // JSON this class encodes and decodes itself.
        useDefaultTransformers = false
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            // OkHttp's own defaults, which this client always ran with; now the same everywhere.
            connectTimeoutMillis = TIMEOUT_MILLIS
            socketTimeoutMillis = TIMEOUT_MILLIS
        }
        // In this order: the header and the token are on the request before ApiErrors sees it.
        install(ProtocolHeader)
        if (tokens != null) install(BearerAuth) { this.tokens = tokens }
        install(ApiErrors) {
            this.json = json
            this.events = events
        }
    }
    return HttpApi(client, baseUrl, json, userAgent)
}

private const val TIMEOUT_MILLIS = 10_000L

// Spelled as OkHttp spelled it for a String body: lower case.
private val JSON_UTF8 = ContentType.Application.Json.withParameter("charset", "utf-8")

/** [Api] over Ktor: each method is one request to its Wire Contract path. */
internal class HttpApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val json: Json,
    private val userAgent: String?,
) : Api {

    private suspend fun send(method: HttpMethod, path: String, body: String? = null, authorization: String? = null): HttpResponse =
        client.request(baseUrl + path) {
            this.method = method
            headers {
                userAgent?.let { append(HttpHeaders.UserAgent, it) }
                authorization?.let { append(HttpHeaders.Authorization, it) }
            }
            if (body != null) setBody(ByteArrayContent(body.encodeToByteArray(), JSON_UTF8))
        }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    private suspend inline fun <reified T> decode(response: HttpResponse): T =
        json.decodeFromString(response.bodyAsChannel().readRemaining().readString())

    /**
     * [value] as one path segment, encoded as Retrofit encoded a path parameter: '/', '%', a
     * space and the other characters a URL cannot hold become %XX of their UTF-8 bytes.
     */
    private fun segment(value: String): String {
        require(value != "." && value != "..") { "A path parameter may not be '$value'" }
        val out = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xff
            if (b < 0x20 || b >= 0x7f || b.toChar() in PATH_SEGMENT_ENCODED) {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
            } else {
                out.append(b.toChar())
            }
        }
        return out.toString()
    }

    private companion object {
        const val PATH_SEGMENT_ENCODED = " \"<>^`{}|\\?#/%"
        const val HEX = "0123456789ABCDEF"
    }

    override suspend fun register(body: RegisterRequest): RegisterResponse =
        decode(send(HttpMethod.Post, "api/v1/register", body = encode(body)))

    override suspend fun login(body: LoginRequest): LoginResponse =
        decode(send(HttpMethod.Post, "api/v1/login", body = encode(body)))

    override suspend fun registrationStatus(): RegistrationStatusResponse =
        decode(send(HttpMethod.Get, "api/v1/registration-status"))

    override suspend fun castCloseVote(listId: String): CloseVoteStateDto =
        decode(send(HttpMethod.Post, "api/v1/lists/${segment(listId)}/close-votes"))

    override suspend fun withdrawCloseVote(listId: String): CloseVoteStateDto =
        decode(send(HttpMethod.Delete, "api/v1/lists/${segment(listId)}/close-votes"))

    override suspend fun logout(authorization: String) {
        send(HttpMethod.Post, "api/v1/logout", authorization = authorization)
    }

    override suspend fun appVersion(): AppVersionResponse =
        decode(send(HttpMethod.Get, "api/v1/app-version"))

    override suspend fun changePassword(body: ChangePasswordRequest) {
        send(HttpMethod.Post, "api/v1/account/change-password", body = encode(body))
    }

    override suspend fun changeEmail(body: ChangeEmailRequest) {
        send(HttpMethod.Post, "api/v1/account/change-email", body = encode(body))
    }

    override suspend fun sessions(): SessionsResponse =
        decode(send(HttpMethod.Get, "api/v1/account/sessions"))

    override suspend fun revokeSession(id: String) {
        send(HttpMethod.Delete, "api/v1/account/sessions/${segment(id)}")
    }

    override suspend fun deleteAccount(body: DeleteAccountRequest) {
        send(HttpMethod.Delete, "api/v1/account", body = encode(body))
    }

    override suspend fun getSettings(): SettingsResponse =
        decode(send(HttpMethod.Get, "api/v1/settings"))

    override suspend fun updateSettings(body: UpdateSettingsRequest): SettingsResponse =
        decode(send(HttpMethod.Patch, "api/v1/settings", body = encode(body)))

    override suspend fun lists(): ListsResponse =
        decode(send(HttpMethod.Get, "api/v1/lists"))

    override suspend fun members(listId: String): MembersResponse =
        decode(send(HttpMethod.Get, "api/v1/lists/${segment(listId)}/members"))

    override suspend fun leaveList(listId: String) {
        send(HttpMethod.Post, "api/v1/lists/${segment(listId)}/leave")
    }

    override suspend fun createInvite(listId: String, body: CreateInviteRequest): CreateInviteResponse =
        decode(send(HttpMethod.Post, "api/v1/lists/${segment(listId)}/invites", body = encode(body)))

    override suspend fun revokeInvite(id: String) {
        send(HttpMethod.Delete, "api/v1/invites/${segment(id)}")
    }

    override suspend fun redeemInvite(body: RedeemInviteRequest): RedeemInviteResponse =
        decode(send(HttpMethod.Post, "api/v1/invites/redeem", body = encode(body)))

    override suspend fun pendingInvites(): PendingInvitesResponse =
        decode(send(HttpMethod.Get, "api/v1/invites/pending"))

    override suspend fun sync(body: SyncRequest): SyncResponse =
        decode(send(HttpMethod.Post, "api/v1/sync", body = encode(body)))

    override suspend fun adminUsers(): AdminUsersResponse =
        decode(send(HttpMethod.Get, "api/v1/admin/users"))

    override suspend fun adminGetServerSettings(): ServerSettingsDto =
        decode(send(HttpMethod.Get, "api/v1/admin/server-settings"))

    override suspend fun adminSetServerSettings(body: ServerSettingsUpdate): ServerSettingsDto =
        decode(send(HttpMethod.Put, "api/v1/admin/server-settings", body = encode(body)))

    override suspend fun adminResetPassword(id: String, body: AdminPasswordRequest): AdminResetPasswordResponse =
        decode(send(HttpMethod.Post, "api/v1/admin/users/${segment(id)}/reset-password", body = encode(body)))

    override suspend fun adminDeleteUser(id: String, body: AdminPasswordRequest) {
        send(HttpMethod.Delete, "api/v1/admin/users/${segment(id)}", body = encode(body))
    }
}
