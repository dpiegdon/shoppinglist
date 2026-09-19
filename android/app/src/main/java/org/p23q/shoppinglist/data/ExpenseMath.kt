package org.p23q.shoppinglist.data

/**
 * Expense arithmetic (T-153). Money is handled in whole cents everywhere below: a float would make
 * a three-way split disagree between two devices by a cent, and the server validates that both
 * share maps of an expense sum to exactly the same value.
 *
 * Every rule here has a twin in the web client's `lib/expenses.ts`, and both are tested against
 * the SAME case table — shared-test-cases/expense-arithmetic.json — so they cannot drift.
 */
object ExpenseMath {

    // [0-9], not \d: the same deliberate choice as the price pattern (T-125). Kotlin's \d is
    // ASCII-only, but writing it explicitly keeps all three clients' patterns visibly identical.
    private val AMOUNT_RE = Regex("^[0-9]+(\\.[0-9]{1,2})?$")

    /** Cents of a wire amount ("12" -> 1200, "12.5" -> 1250); null for anything unparseable. */
    fun toCents(amount: String): Long? {
        val trimmed = amount.trim()
        if (!AMOUNT_RE.matches(trimmed)) return null
        val whole = trimmed.substringBefore('.')
        val fraction = trimmed.substringAfter('.', "").padEnd(2, '0')
        return whole.toLong() * 100 + if (fraction.isEmpty()) 0 else fraction.toLong()
    }

    /** A cent count back to the wire format, always two decimals ("-2133" -> "-21.33"). */
    fun fromCents(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val absolute = kotlin.math.abs(cents)
        return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
    }

    /** What the user typed for one participant: an amount they fixed, or null for "share the rest". */
    data class ShareEntry(val id: String, val fixed: Long? = null)

    enum class DistributeError { TOTAL_NOT_POSITIVE, NO_PARTICIPANTS, FIXED_EXCEEDS_TOTAL, FIXED_SUM_MISMATCH }

    sealed interface DistributeResult {
        data class Shares(val shares: Map<String, Long>) : DistributeResult
        data class Failed(val error: DistributeError) : DistributeResult
    }

    /**
     * Split [totalCents] equally between [ids].
     *
     * The leftover cents of an uneven division go one each to the first participants in the order
     * given, so every client shows the same numbers. A participant whose share rounds to nothing
     * is left out entirely rather than carried at zero, which is also what the wire format demands.
     */
    fun splitEqually(totalCents: Long, ids: List<String>): Map<String, Long> {
        if (ids.isEmpty()) return emptyMap()
        val base = totalCents / ids.size
        var leftover = totalCents - base * ids.size
        val shares = LinkedHashMap<String, Long>()
        for (id in ids) {
            val share = base + if (leftover > 0) 1 else 0
            if (leftover > 0) leftover -= 1
            if (share > 0) shares[id] = share
        }
        return shares
    }

    /**
     * The form's whole model: the total drives, fixed shares stay as typed, and everything still
     * on auto absorbs the difference equally.
     *
     * Two states are refusals rather than silent corrections. Fixed shares above the total cannot
     * be made to add up at all. A distribution where everything is fixed but the sum misses the
     * total could in principle move the total instead — but a total that changes underneath you
     * while you edit shares is how wrong numbers get saved, so the form asks.
     */
    fun distribute(totalCents: Long, entries: List<ShareEntry>): DistributeResult {
        if (totalCents <= 0) return DistributeResult.Failed(DistributeError.TOTAL_NOT_POSITIVE)
        if (entries.isEmpty()) return DistributeResult.Failed(DistributeError.NO_PARTICIPANTS)

        val fixed = entries.filter { it.fixed != null }
        val auto = entries.filter { it.fixed == null }
        val remainder = totalCents - fixed.sumOf { it.fixed ?: 0 }
        if (remainder < 0) return DistributeResult.Failed(DistributeError.FIXED_EXCEEDS_TOTAL)
        if (auto.isEmpty() && remainder != 0L) {
            return DistributeResult.Failed(DistributeError.FIXED_SUM_MISMATCH)
        }

        val shares = LinkedHashMap<String, Long>()
        for (entry in fixed) {
            val amount = entry.fixed ?: 0
            if (amount > 0) shares[entry.id] = amount
        }
        shares.putAll(splitEqually(remainder, auto.map { it.id }))
        return DistributeResult.Shares(shares)
    }

    /** Cents back to the wire's decimal strings, for pushing. */
    fun sharesToWire(shares: Map<String, Long>): Map<String, String> =
        shares.mapValues { (_, cents) -> fromCents(cents) }

    /** Total of one share map, in cents; an unparseable amount counts as nothing. */
    fun mapTotalCents(shares: Map<String, String>): Long =
        shares.values.sumOf { toCents(it) ?: 0L }

    /** An expense's total: the sum of either map, since the server guarantees the two agree. */
    fun expenseTotalCents(expense: Expense): Long = mapTotalCents(expense.paidBy)

    data class Balance(
        val accountId: String,
        val paidCents: Long,
        val shareCents: Long,
        /** What the list owes them: positive is a credit, negative a debt. */
        val balanceCents: Long,
    )

    /**
     * Per-participant totals over a list's expenses.
     *
     * Everyone named anywhere is included, whether or not they are still a member — debts and
     * credits do not disappear when someone leaves — and so is a member who has spent nothing.
     * Largest credit first, then by id so the order is stable — plain string comparison, never a
     * locale-aware one (T-204): ids are opaque and the order must not depend on who is looking.
     * The shared case table pins it, as it does [settle]'s.
     *
     * The balances always sum to zero, because every expense's two maps sum to the same amount.
     */
    fun balancesFor(expenses: List<Expense>, participantIds: List<String>): List<Balance> {
        val paid = LinkedHashMap<String, Long>()
        val share = LinkedHashMap<String, Long>()
        for (id in participantIds) {
            paid.putIfAbsent(id, 0)
            share.putIfAbsent(id, 0)
        }
        for (expense in expenses) {
            for ((id, amount) in expense.paidBy) {
                paid[id] = (paid[id] ?: 0) + (toCents(amount) ?: 0)
                share.putIfAbsent(id, 0)
            }
            for ((id, amount) in expense.paidFor) {
                share[id] = (share[id] ?: 0) + (toCents(amount) ?: 0)
                paid.putIfAbsent(id, 0)
            }
        }
        return paid.keys
            .map { id ->
                val paidCents = paid[id] ?: 0
                val shareCents = share[id] ?: 0
                Balance(id, paidCents, shareCents, paidCents - shareCents)
            }
            .sortedWith(compareByDescending<Balance> { it.balanceCents }.thenBy { it.accountId })
    }

    /**
     * How to label participants who are no longer members (T-152): numbered by first appearance
     * across the expenses in the order they are shown, so two of them stay distinguishable and
     * each keeps a stable number.
     */
    fun formerMemberNumbers(expenses: List<Expense>, memberIds: Set<String>): Map<String, Int> {
        val numbers = LinkedHashMap<String, Int>()
        for (expense in expenses) {
            for (id in expense.paidBy.keys + expense.paidFor.keys) {
                if (id !in memberIds && id !in numbers) numbers[id] = numbers.size + 1
            }
        }
        return numbers
    }

    /** One payment that settling up asks for: [from] pays [to]. */
    data class Transfer(val from: String, val to: String, val cents: Long)

    /**
     * Who pays whom to bring every balance to zero (T-163). Greedy: the largest debtor pays the
     * largest creditor the smaller of the two amounts, whoever reaches zero drops out, repeat.
     * Exact in cents, and at most one transfer fewer than the number of people with a balance.
     * Not always the fewest transfers possible — that problem is NP-hard, and this is what every
     * app in this space shows. Ties in amount go by account id, so both clients list the same
     * transfers in the same order; the shared case table pins that.
     */
    fun settle(balances: List<Balance>): List<Transfer> {
        val debts = LinkedHashMap<String, Long>()
        val credits = LinkedHashMap<String, Long>()
        for (balance in balances) {
            if (balance.balanceCents < 0) debts[balance.accountId] = -balance.balanceCents
            if (balance.balanceCents > 0) credits[balance.accountId] = balance.balanceCents
        }
        // Plain String order, not a Collator: ids are opaque and the order must not depend on the
        // viewer's locale.
        val byAmountThenId = compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
        val transfers = mutableListOf<Transfer>()
        while (true) {
            val (debtorId, debt) = debts.entries.minWithOrNull(byAmountThenId) ?: break
            val (creditorId, credit) = credits.entries.minWithOrNull(byAmountThenId) ?: break
            val cents = minOf(debt, credit)
            transfers += Transfer(debtorId, creditorId, cents)
            if (debt == cents) debts.remove(debtorId) else debts[debtorId] = debt - cents
            if (credit == cents) credits.remove(creditorId) else credits[creditorId] = credit - cents
        }
        return transfers
    }
}
