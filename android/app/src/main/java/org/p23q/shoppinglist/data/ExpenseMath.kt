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
    //
    // The whole part is capped at 13 digits (T-262): unbounded, this matched a 20-digit price the
    // server's old 32-CHARACTER cap let through, and `whole.toLong()` below threw
    // NumberFormatException — reachable from another device's price, so it crashed this screen for
    // every member on every render. 13 digits is the largest width for which the cents value
    // (whole * 100 + fraction, up to 999999999999999) can NEVER exceed JS's
    // Number.MAX_SAFE_INTEGER (9007199254740991): the web's `toCents` shares this bound so a price
    // neither client can represent exactly is refused up front by the server rather than shown
    // wrong by one of them. It is nowhere near Kotlin's own Long ceiling.
    private val AMOUNT_RE = Regex("^[0-9]{1,13}(\\.[0-9]{1,2})?$")

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

    /**
     * Which of the three an entry is (T-245). An absent type is an [ExpenseType.EXPENSE] — that is
     * every entry written before the type existed — and so is anything unrecognised: the server
     * refuses those, so one arriving here is a newer client's word this one does not know yet, and
     * reading it as an ordinary expense keeps the screen arithmetic sane instead of dropping the
     * entry.
     */
    fun entryType(expense: Expense): ExpenseType = when (expense.type) {
        ExpenseType.INCOME.wire -> ExpenseType.INCOME
        ExpenseType.TRANSFER.wire -> ExpenseType.TRANSFER
        else -> ExpenseType.EXPENSE
    }

    /**
     * What one entry does to one account's balance, in signed cents (T-245).
     *
     * An expense credits whoever paid and debits whoever it was for; an income is the same reading
     * mirrored, because money coming in owes the group rather than the other way round; a transfer
     * moves a debt from the sender to the recipient. Zero when the account is not on the entry.
     */
    fun entryEffectCents(expense: Expense, accountId: String): Long {
        val net = (toCents(expense.paidBy[accountId] ?: "0") ?: 0) -
            (toCents(expense.paidFor[accountId] ?: "0") ?: 0)
        return if (entryType(expense) == ExpenseType.INCOME) -net else net
    }

    /** What a ledger has spent: expenses, income and the net of the two, in cents. */
    data class SpentTotals(
        val expensesCents: Long,
        val incomeCents: Long,
        /** Expenses minus income. Negative when a ledger has taken in more than it laid out. */
        val netCents: Long,
    )

    /**
     * Net spent over a ledger (T-245). Transfers count for nothing: settling up moves money
     * between members without the group having spent or received a thing.
     */
    fun spentTotals(expenses: List<Expense>): SpentTotals {
        var expensesCents = 0L
        var incomeCents = 0L
        for (expense in expenses) {
            when (entryType(expense)) {
                ExpenseType.EXPENSE -> expensesCents += expenseTotalCents(expense)
                ExpenseType.INCOME -> incomeCents += expenseTotalCents(expense)
                ExpenseType.TRANSFER -> Unit
            }
        }
        return SpentTotals(expensesCents, incomeCents, expensesCents - incomeCents)
    }

    data class Balance(
        val accountId: String,
        /** What they laid out on expenses, less what they took in on income. */
        val paidCents: Long,
        /** What they consumed of the expenses, less what income was credited to them. */
        val shareCents: Long,
        /** Transfers: what they have sent, less what they have received. */
        val settledCents: Long = 0,
        /** What the list owes them: positive is a credit, negative a debt. */
        val balanceCents: Long,
    )

    /** The three running figures of one participant while [balancesFor] adds a ledger up. */
    private data class Totals(var paid: Long = 0, var share: Long = 0, var settled: Long = 0)

    /**
     * Per-participant totals over a list's entries.
     *
     * Everyone named anywhere is included, whether or not they are still a member — debts and
     * credits do not disappear when someone leaves — and so is a member who has spent nothing.
     * Largest credit first, then by id so the order is stable — plain string comparison, never a
     * locale-aware one (T-204): ids are opaque and the order must not depend on who is looking.
     * The shared case table pins it, as it does [settle]'s.
     *
     * Each type lands in its own figure (T-245). An income is an expense read backwards, so it
     * comes off `paid` and `share` rather than adding to them. A transfer goes into `settled`
     * alone — deliberately out of `paid` and `share`, so those two keep meaning "what this person
     * laid out / consumed" and paying a debt back never looks like more spending. The three then
     * add up: `balance = paid - share + settled`.
     *
     * The balances always sum to zero, because every entry's two maps sum to the same amount
     * whichever figures they land in.
     */
    fun balancesFor(expenses: List<Expense>, participantIds: List<String>): List<Balance> {
        val totals = LinkedHashMap<String, Totals>()
        fun touch(id: String): Totals = totals.getOrPut(id) { Totals() }
        for (id in participantIds) touch(id)
        for (expense in expenses) {
            val type = entryType(expense)
            for ((id, amount) in expense.paidBy) {
                val cents = toCents(amount) ?: 0
                when (type) {
                    ExpenseType.TRANSFER -> touch(id).settled += cents
                    ExpenseType.INCOME -> touch(id).paid -= cents
                    ExpenseType.EXPENSE -> touch(id).paid += cents
                }
            }
            for ((id, amount) in expense.paidFor) {
                val cents = toCents(amount) ?: 0
                when (type) {
                    ExpenseType.TRANSFER -> touch(id).settled -= cents
                    ExpenseType.INCOME -> touch(id).share -= cents
                    ExpenseType.EXPENSE -> touch(id).share += cents
                }
            }
        }
        return totals
            .map { (id, running) ->
                Balance(
                    accountId = id,
                    paidCents = running.paid,
                    shareCents = running.share,
                    settledCents = running.settled,
                    balanceCents = running.paid - running.share + running.settled,
                )
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
