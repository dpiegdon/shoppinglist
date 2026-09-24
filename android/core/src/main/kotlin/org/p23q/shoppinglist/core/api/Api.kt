package org.p23q.shoppinglist.core.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.IOException

/**
 * A non-2xx response whose body was a Wire Contract error envelope ({"error", "message"}).
 * Extends IOException, not Exception: OkHttp interceptors may only throw IOException — anything
 * else gets caught by RealCall and rewrapped as a generic IOException, losing this type entirely.
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
) : IOException(message)

/** 401 responses always mean the caller must re-authenticate. */
class UnauthorizedException(message: String) : ApiException("unauthorized", message, 401)

/**
 * Retrofit interface mirroring the Wire Contract. Every path is relative (no leading '/') so the
 * configured server URL's own path prefix (the Flask blueprint may be mounted under a subpath,
 * e.g. https://host/my/stuff/shoppinglist/) is preserved rather than discarded — see ServerConfig.
 */
interface Api {
    @POST("api/v1/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("api/v1/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /**
     * Whether this server currently accepts new accounts (T-276). Unauthenticated, same shape as
     * the admin server-settings endpoint ({"allow_registration"}), so it reuses [ServerSettingsDto].
     */
    @GET("api/v1/registration-status")
    suspend fun registrationStatus(): ServerSettingsDto

    /** Agree to close an expenses list (T-157); it closes when the last current member agrees. */
    @POST("api/v1/lists/{listId}/close-votes")
    suspend fun castCloseVote(@Path("listId") listId: String): CloseVoteStateDto

    @DELETE("api/v1/lists/{listId}/close-votes")
    suspend fun withdrawCloseVote(@Path("listId") listId: String): CloseVoteStateDto

    @POST("api/v1/logout")
    suspend fun logout()

    /**
     * The app version this server carries (T-135). Unauthenticated. A 404 means "no app package
     * here" — which is also what every server released before this endpoint existed returns, so
     * the client needs only one code path for "nothing to say".
     */
    @GET("api/v1/app-version")
    suspend fun appVersion(): AppVersionResponse

    @POST("api/v1/account/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest)

    @POST("api/v1/account/change-email")
    suspend fun changeEmail(@Body body: ChangeEmailRequest)

    @GET("api/v1/account/sessions")
    suspend fun sessions(): SessionsResponse

    @DELETE("api/v1/account/sessions/{id}")
    suspend fun revokeSession(@Path("id") id: String)

    @HTTP(method = "DELETE", path = "api/v1/account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest)

    @GET("api/v1/settings")
    suspend fun getSettings(): SettingsResponse

    @PATCH("api/v1/settings")
    suspend fun updateSettings(@Body body: UpdateSettingsRequest): SettingsResponse

    @GET("api/v1/lists")
    suspend fun lists(): ListsResponse

    @GET("api/v1/lists/{id}/members")
    suspend fun members(@Path("id") listId: String): MembersResponse

    @POST("api/v1/lists/{id}/leave")
    suspend fun leaveList(@Path("id") listId: String)

    @POST("api/v1/lists/{id}/invites")
    suspend fun createInvite(@Path("id") listId: String, @Body body: CreateInviteRequest): CreateInviteResponse

    @DELETE("api/v1/invites/{id}")
    suspend fun revokeInvite(@Path("id") id: String)

    @POST("api/v1/invites/redeem")
    suspend fun redeemInvite(@Body body: RedeemInviteRequest): RedeemInviteResponse

    /** The invites addressed to the signed-in account that are still open to join (T-233). */
    @GET("api/v1/invites/pending")
    suspend fun pendingInvites(): PendingInvitesResponse

    @POST("api/v1/sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse

    // --- Admin (T-107) ---

    @GET("api/v1/admin/users")
    suspend fun adminUsers(): AdminUsersResponse

    @GET("api/v1/admin/server-settings")
    suspend fun adminGetServerSettings(): ServerSettingsDto

    @PUT("api/v1/admin/server-settings")
    suspend fun adminSetServerSettings(@Body body: ServerSettingsDto): ServerSettingsDto

    @POST("api/v1/admin/users/{id}/reset-password")
    suspend fun adminResetPassword(
        @Path("id") id: String,
        @Body body: AdminPasswordRequest,
    ): AdminResetPasswordResponse

    @HTTP(method = "DELETE", path = "api/v1/admin/users/{id}", hasBody = true)
    suspend fun adminDeleteUser(@Path("id") id: String, @Body body: AdminPasswordRequest)
}
