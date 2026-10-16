package org.p23q.shoppinglist.core.api

import kotlinx.io.IOException

/**
 * A non-2xx response, with what its Wire Contract error envelope ({"error", "message"}) said.
 * An IOException, as every failure to get an answer from the server is: a caller that only needs
 * to know the request did not succeed catches that one type.
 */
open class ApiException(
    val code: String,
    message: String,
    val httpStatus: Int,
    /** For a /sync validation 422: the offending row + field, so the client can quarantine it (T-32). */
    val rowId: String? = null,
    val field: String? = null,
    /** The account a 422 named, where its code carries one — participant_frozen does (T-200). */
    val accountId: String? = null,
    /**
     * The server's protocol version, where the error envelope carries one: the `no_app_package`
     * 404 from `/app-version` does (T-297).
     */
    val protocol: Int? = null,
    /** The server's release, which the `no_app_package` 404 also carries (T-327). */
    val version: String? = null,
) : IOException(message)

/** 401 responses always mean the caller must re-authenticate. */
class UnauthorizedException(message: String) : ApiException("unauthorized", message, 401)

/**
 * The Wire Contract's endpoints, one method each; [createApi] builds the client. Every path is
 * relative to the account's server URL, so its own path prefix (the Flask blueprint may be
 * mounted under a subpath, e.g. https://host/my/stuff/shoppinglist/) is kept. A failed request
 * throws an [IOException]: an [ApiException] when the server refused it, another one when no
 * answer came. An answer that is not the endpoint's JSON throws a SerializationException.
 */
interface Api {
    suspend fun register(body: RegisterRequest): RegisterResponse

    suspend fun login(body: LoginRequest): LoginResponse

    /**
     * Whether this server currently accepts new accounts (T-276), and its server message (T-315).
     * Unauthenticated.
     */
    suspend fun registrationStatus(): RegistrationStatusResponse

    /** Agree to close an expenses list (T-157); it closes when the last current member agrees. */
    suspend fun castCloseVote(listId: String): CloseVoteStateDto

    suspend fun withdrawCloseVote(listId: String): CloseVoteStateDto

    /**
     * Ends the session [authorization] (`Bearer <token>`) names. Only the unbound client sends it,
     * for a sign-in the phone refuses after the server accepted it (T-300): nothing signs out a
     * stored session, whose client would add its own token beside this one.
     */
    suspend fun logout(authorization: String)

    /**
     * The app version this server carries (T-135). Unauthenticated. A 404 means "no app package
     * here" — which is also what every server released before this endpoint existed returns, so
     * the client needs only one code path for "nothing to say".
     */
    suspend fun appVersion(): AppVersionResponse

    suspend fun changePassword(body: ChangePasswordRequest)

    suspend fun changeEmail(body: ChangeEmailRequest)

    suspend fun sessions(): SessionsResponse

    suspend fun revokeSession(id: String)

    suspend fun deleteAccount(body: DeleteAccountRequest)

    suspend fun getSettings(): SettingsResponse

    suspend fun updateSettings(body: UpdateSettingsRequest): SettingsResponse

    suspend fun lists(): ListsResponse

    suspend fun members(listId: String): MembersResponse

    suspend fun leaveList(listId: String)

    suspend fun createInvite(listId: String, body: CreateInviteRequest): CreateInviteResponse

    suspend fun revokeInvite(id: String)

    suspend fun redeemInvite(body: RedeemInviteRequest): RedeemInviteResponse

    /** The invites addressed to the signed-in account that are still open to join (T-233). */
    suspend fun pendingInvites(): PendingInvitesResponse

    suspend fun sync(body: SyncRequest): SyncResponse

    // --- Admin (T-107) ---

    suspend fun adminUsers(): AdminUsersResponse

    suspend fun adminGetServerSettings(): ServerSettingsDto

    suspend fun adminSetServerSettings(body: ServerSettingsUpdate): ServerSettingsDto

    suspend fun adminResetPassword(
        id: String,
        body: AdminPasswordRequest,
    ): AdminResetPasswordResponse

    suspend fun adminDeleteUser(id: String, body: AdminPasswordRequest)
}
