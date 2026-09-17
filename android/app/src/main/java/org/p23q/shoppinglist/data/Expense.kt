package org.p23q.shoppinglist.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The whole money tuple of an expense (T-151), and ONE LWW field on the item.
 *
 * It is one field rather than several because the invariant that matters spans both maps — they
 * sum to the same amount — and per-field last-write-wins would let two offline edits break it
 * between them. There is no stored total: it is the sum of either map.
 *
 * Amounts are positive decimal strings in the wire format, and a participant with no share is
 * absent from the map rather than present with a zero. [equalBy] / [equalFor] record that a map
 * was an equal split, so reopening the form redistributes on a changed total instead of refusing.
 *
 * Mirrors the web client's `Expense` in api/contract.ts.
 */
@Serializable
data class Expense(
    @SerialName("paid_by") val paidBy: Map<String, String>,
    @SerialName("equal_by") val equalBy: Boolean,
    @SerialName("paid_for") val paidFor: Map<String, String>,
    @SerialName("equal_for") val equalFor: Boolean,
    /** Calendar date, YYYY-MM-DD: no time, no zone. */
    val date: String,
)

/**
 * One entry of a list's roster, as the server maintains it on the synced list object (T-152).
 *
 * Carried on the list row rather than fetched, so the expense form's defaults and the balances
 * screen work offline. [initials] arrives already resolved (override or derived from the email).
 */
@Serializable
data class ListMember(
    @SerialName("account_id") val accountId: String,
    val email: String,
    val initials: String,
)
