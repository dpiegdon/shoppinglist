package org.p23q.shoppinglist.data.api

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
    val deleted: FieldClock<Boolean>,
)

@Serializable
data class ListDto(
    val id: String,
    @SerialName("created_at") val createdAt: Long,
    val fields: ListFieldsDto,
)

@Serializable
data class ErrorEnvelope(
    val error: String,
    val message: String,
    @SerialName("row_id") val rowId: String? = null,
    val field: String? = null,
)

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class RegisterResponse(@SerialName("account_id") val accountId: String)

@Serializable
data class LoginRequest(val email: String, val password: String, @SerialName("device_label") val deviceLabel: String)

@Serializable
data class LoginResponse(val token: String, @SerialName("account_id") val accountId: String, val email: String)

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
)
