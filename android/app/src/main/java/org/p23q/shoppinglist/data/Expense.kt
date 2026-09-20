package org.p23q.shoppinglist.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a ledger entry is (T-245). Every amount on the wire stays positive whichever it is: the
 * sign lives in the type, so an entry is structurally exactly one of the three.
 *
 * - [EXPENSE]: someone paid for the group. `paid_by` / `paid_for`.
 * - [INCOME]: the group received money (a refund, a deposit, a sale). The same two maps read
 *   "received by" / "credited to", and the entry counts against what was spent.
 * - [TRANSFER]: one member pays another directly — a settlement. Exactly one sender in `paid_by`
 *   and one different recipient in `paid_for`; it moves a debt without spending anything.
 *
 * Mirrors the web client's `ExpenseType` in api/contract.ts.
 */
enum class ExpenseType(val wire: String) {
    EXPENSE("expense"),
    INCOME("income"),
    TRANSFER("transfer"),
}

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
    /**
     * Which of the three this entry is (T-245), as the wire spells it. A String rather than
     * [ExpenseType] on purpose: null is every entry written before the type existed, and a word
     * this app does not know is a newer client's vocabulary — neither may make the whole entry
     * fail to decode and vanish off the screen. [ExpenseMath.entryType] reads it, and both cases
     * come back as an ordinary expense. Last so that the four fields an entry has always had keep
     * their positions; the wire is a JSON object, where order means nothing.
     */
    val type: String? = null,
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
