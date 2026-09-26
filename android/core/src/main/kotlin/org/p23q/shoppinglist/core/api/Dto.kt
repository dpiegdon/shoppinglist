package org.p23q.shoppinglist.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire Contract field clock: every syncable field value carries its own last-write-wins stamp. */
@Serializable
data class FieldClock<T>(
    val value: T,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("updated_by") val updatedBy: String,
)

@Serializable
data class PriceDto(val amount: String, val currency: String?)

@Serializable
data class ItemFieldsDto(
    val name: FieldClock<String>,
    val category: FieldClock<String?>,
    val stores: FieldClock<List<String>>,
    val quantity: FieldClock<String?>,
    val price: FieldClock<PriceDto?>,
    val note: FieldClock<String?>,
    val status: FieldClock<String>,
    /**
     * The expense money tuple (T-151), null except on an expenses list. DEFAULTED like `kind`
     * below, so this app still decodes items from a server too old to send it at all.
     */
    val expense: FieldClock<org.p23q.shoppinglist.core.Expense?> = FieldClock(null, 0, ""),
    val deleted: FieldClock<Boolean>,
)

@Serializable
data class ItemDto(
    val id: String,
    @SerialName("list_id") val listId: String,
    @SerialName("created_at") val createdAt: Long,
    val fields: ItemFieldsDto,
    // Whole-item, account-scoped (T-64) — not a per-field LWW clock, so it rides outside `fields`.
    // Null until the item's first edit after this column existed server-side.
    @SerialName("last_touched_by") val lastTouchedBy: String? = null,
)

@Serializable
data class ListFieldsDto(
    val name: FieldClock<String>,
    @SerialName("category_order") val categoryOrder: FieldClock<List<String>>,
    val notes: FieldClock<String?>,
    /**
     * List kind (T-110). DEFAULTED so this app still decodes lists from a pre-T-110 server, which
     * sends no `kind` at all — without the default, kotlinx would throw on every list and break
     * sync entirely. A missing kind means "shopping", with clock 0 so any real write wins.
     */
    val kind: FieldClock<String> = FieldClock(org.p23q.shoppinglist.core.ListKind.DEFAULT, 0, ""),
    /** Free-text currency label of an expenses list (T-151); defaulted for older servers. */
    val currency: FieldClock<String?> = FieldClock(null, 0, ""),
    val deleted: FieldClock<Boolean>,
)

@Serializable
data class ListDto(
    val id: String,
    @SerialName("created_at") val createdAt: Long,
    val fields: ListFieldsDto,
    // Server-maintained, outside `fields` like an item's last_touched_by (T-152): the client never
    // writes these, it mirrors whatever the server last reported. All defaulted, so a server
    // predating them still decodes.
    val members: List<org.p23q.shoppinglist.core.ListMember> = emptyList(),
    @SerialName("close_votes") val closeVotes: List<String> = emptyList(),
    @SerialName("closed_at") val closedAt: Long? = null,
)

/** What the close-vote endpoints answer with (T-157). */
@Serializable
data class CloseVoteStateDto(
    @SerialName("close_votes") val closeVotes: List<String>,
    @SerialName("closed_at") val closedAt: Long? = null,
)

@Serializable
data class ErrorEnvelope(
    val error: String,
    val message: String,
    @SerialName("row_id") val rowId: String? = null,
    val field: String? = null,
    /** Which participant a participant_frozen refusal is about (T-200). */
    @SerialName("account_id") val accountId: String? = null,
    /** The server's protocol version, on a `no_app_package` 404 from `/app-version` (T-297). */
    val protocol: Int? = null,
)

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class RegisterResponse(@SerialName("account_id") val accountId: String)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_label") val deviceLabel: String,
    // Picks the session's server-side inactivity window (T-104): "android" buys the
    // long one. The server maps this to a duration itself and falls back to its own
    // default for anything it doesn't recognize, so this is a hint, never a duration.
    // Non-null with no default so kotlinx always emits it (encodeDefaults=false).
    val platform: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("account_id") val accountId: String,
    val email: String,
    // Whether this account is a configured admin (T-107); drives the admin screen. Defaulted so a
    // pre-T-107 server that omits it decodes as non-admin.
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

// --- Admin (T-107) ---

@Serializable
data class AdminUserDto(
    val id: String,
    val email: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("session_count") val sessionCount: Int,
    @SerialName("is_admin") val isAdmin: Boolean,
)

@Serializable
data class AdminUsersResponse(val users: List<AdminUserDto>)

/**
 * GET and PUT /admin/server-settings: both current values. [message] is the one-line server
 * message every client shows (T-315), "" when none; null (absent) from a server before it, which
 * would refuse a request that sets only the message, so the console then offers no field.
 */
@Serializable
data class ServerSettingsDto(
    @SerialName("allow_registration") val allowRegistration: Boolean,
    val message: String? = null,
)

/**
 * PUT /admin/server-settings is partial (T-315): a null field is left out of the wire (this app's
 * Json has encodeDefaults=false) and the server leaves that setting unchanged. At least one is set.
 */
@Serializable
data class ServerSettingsUpdate(
    @SerialName("allow_registration") val allowRegistration: Boolean? = null,
    val message: String? = null,
)

/** GET /registration-status (T-276): unauthenticated; [message] is the server message, null when none (T-315). */
@Serializable
data class RegistrationStatusResponse(
    @SerialName("allow_registration") val allowRegistration: Boolean,
    val message: String? = null,
)

/** Step-up: the ADMIN's own password, for a destructive admin action. */
@Serializable
data class AdminPasswordRequest(val password: String)

@Serializable
data class AdminResetPasswordResponse(val password: String)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ChangeEmailRequest(val password: String, @SerialName("new_email") val newEmail: String)

@Serializable
data class SessionDto(
    val id: String,
    // Nullable: a real dev-server check (A10) showed the server returns null device_label for
    // sessions from clients that didn't send one (e.g. curl) - unlike LoginRequest.deviceLabel,
    // which this app always fills in when it logs in, but other clients aren't guaranteed to.
    @SerialName("device_label") val deviceLabel: String?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen_at") val lastSeenAt: Long,
    val current: Boolean,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionDto>)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class SettingsResponse(
    @SerialName("default_currency") val defaultCurrency: String,
    // Resolved default-or-override (T-64) — never absent.
    val initials: String,
)

// initials: T-64/T-87/T-97. The server treats an ABSENT initials key as "leave unchanged" (T-87)
// — a *present* "" still overwrites a custom override, so callers must only send a real,
// known value. `initials = null` (the default) omits the key from the wire entirely: with this
// app's configured Json (JsonModule.provideJson — encodeDefaults left at its library default of
// false), a property equal to its declared default is elided from the encoded JSON rather than
// sent as literal null. This lets a currency-only save skip initials when it isn't known yet
// (e.g. offline start racing the best-effort preload) instead of clobbering the override with an
// unresolved empty string (T-97, mirrors web's T-101).
@Serializable
data class UpdateSettingsRequest(
    @SerialName("default_currency") val defaultCurrency: String,
    val initials: String? = null,
)

@Serializable
data class ListSummaryDto(val id: String, val name: String, @SerialName("category_order") val categoryOrder: List<String>)

@Serializable
data class ListsResponse(val lists: List<ListSummaryDto>)

@Serializable
data class MemberDto(
    @SerialName("account_id") val accountId: String,
    val email: String,
    val initials: String,
    @SerialName("joined_at") val joinedAt: Long,
)

@Serializable
data class PendingInviteDto(
    val id: String,
    @SerialName("invited_email") val invitedEmail: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class MembersResponse(val members: List<MemberDto>, val invites: List<PendingInviteDto>)

@Serializable
data class CreateInviteRequest(@SerialName("invited_email") val invitedEmail: String)

@Serializable
data class CreateInviteResponse(
    @SerialName("invite_id") val inviteId: String,
    val token: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** An invite waiting for the signed-in account, as the overview offers it (T-233). */
@Serializable
data class InviteForMeDto(
    val id: String,
    @SerialName("list_id") val listId: String,
    @SerialName("list_name") val listName: String,
    @SerialName("list_kind") val listKind: String,
    @SerialName("invited_by_initials") val invitedByInitials: String,
    @SerialName("expires_at") val expiresAt: Long,
    /** The same token the share URL carries; joining is a plain redeem. */
    val token: String,
)

@Serializable
data class PendingInvitesResponse(val invites: List<InviteForMeDto>)

@Serializable
data class RedeemInviteRequest(val token: String)

@Serializable
data class RedeemInviteResponse(@SerialName("list_id") val listId: String)

@Serializable
data class SyncChanges(
    val lists: List<ListDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
)

@Serializable
data class SyncRequest(
    val cursor: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("full_lists") val fullLists: List<String> = emptyList(),
    val changes: SyncChanges = SyncChanges(),
)

@Serializable
data class SyncResponse(
    val cursor: Long,
    val changes: SyncChanges,
    /** The server message (T-315), on every response; null when none, or from a server before it. */
    @SerialName("server_message") val serverMessage: String? = null,
)

/**
 * Response of GET /api/v1/app-version (T-135): the app package this server offers.
 *
 * [protocol] (T-243, T-265) is this server's PROTOCOL_VERSION — the one endpoint a client refused
 * with `426 client_outdated` can still reach, so it is where such a client reads what it must
 * catch up to. Nullable: a server running between T-135 and T-243 answers this endpoint without
 * it.
 */
@Serializable
data class AppVersionResponse(
    val version: String,
    @SerialName("download_url") val downloadUrl: String,
    val protocol: Int? = null,
)
