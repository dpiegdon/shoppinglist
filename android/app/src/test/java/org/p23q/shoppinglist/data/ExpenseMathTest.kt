package org.p23q.shoppinglist.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The split and balance arithmetic (T-153), driven by the case table this shares with the web
 * client: shared-test-cases/expense-arithmetic.json.
 *
 * ONE table, read by both suites, because two copies drift — which is exactly how the
 * cross-client translation fixture went wrong (T-148). If this file cannot find the table, that
 * is a failure and not a skip: silently testing nothing is the outcome worth guarding against.
 */
class ExpenseMathTest {

    private val cases: JsonObject = run {
        // Unit tests run with the MODULE directory (android/app) as their working directory — the
        // same assumption TranslationResourcesTest makes about src/main/res — so the repo root,
        // where the shared table lives, is two levels up.
        val file = File("../../shared-test-cases/expense-arithmetic.json")
        assertTrue("missing shared case table at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun group(name: String) = cases[name]!!.jsonArray.map { it.jsonObject }

    private fun cents(amount: String): Long =
        ExpenseMath.toCents(amount) ?: error("case table has an unparseable amount: $amount")

    private fun shares(obj: JsonObject): Map<String, String> =
        obj.mapValues { (_, value) -> value.jsonPrimitive.content }

    // ---- cents conversion ---------------------------------------------------

    @Test
    fun `wire amounts convert to cents`() {
        for (case in group("to_cents")) {
            val amount = case["amount"]!!.jsonPrimitive.content
            assertEquals(amount, case["cents"]!!.jsonPrimitive.long, cents(amount))
        }
    }

    @Test
    fun `cents render back to wire amounts`() {
        for (case in group("from_cents")) {
            val count = case["cents"]!!.jsonPrimitive.long
            assertEquals("$count", case["amount"]!!.jsonPrimitive.content, ExpenseMath.fromCents(count))
        }
    }

    @Test
    fun `anything the wire format would reject is not a number`() {
        for (bad in listOf("", "1.234", "1,50", "-1", "abc", "٥")) {
            assertNull(bad, ExpenseMath.toCents(bad))
        }
    }

    @Test
    fun `a negative balance keeps its sign`() {
        assertEquals("-21.33", ExpenseMath.fromCents(-2133))
    }

    // ---- the table ----------------------------------------------------------

    @Test
    fun `equal splits match the shared case table`() {
        for (case in group("equal_split")) {
            val name = case["name"]!!.jsonPrimitive.content
            val ids = case["participants"]!!.jsonArray.map { it.jsonPrimitive.content }
            val actual = ExpenseMath.splitEqually(cents(case["total"]!!.jsonPrimitive.content), ids)
            assertEquals(name, shares(case["expect"]!!.jsonObject), ExpenseMath.sharesToWire(actual))
        }
    }

    @Test
    fun `distribution matches the shared case table`() {
        for (case in group("distribute")) {
            val name = case["name"]!!.jsonPrimitive.content
            val entries = case["entries"]!!.jsonArray.map { entry ->
                val obj = entry.jsonObject
                ExpenseMath.ShareEntry(
                    id = obj["id"]!!.jsonPrimitive.content,
                    fixed = obj["fixed"]?.jsonPrimitive?.contentOrNull?.let { cents(it) },
                )
            }
            val result = ExpenseMath.distribute(cents(case["total"]!!.jsonPrimitive.content), entries)

            val expectedError = case["error"]?.jsonPrimitive?.contentOrNull
            if (expectedError != null) {
                val failed = result as? ExpenseMath.DistributeResult.Failed
                assertEquals(name, expectedError.uppercase(), failed?.error?.name)
            } else {
                val shares = (result as? ExpenseMath.DistributeResult.Shares)?.shares
                assertEquals(name, shares(case["expect"]!!.jsonObject), shares?.let(ExpenseMath::sharesToWire))
            }
        }
    }

    /** One entry of the table, with its optional type (T-245): absent means an ordinary expense. */
    private fun expenseOf(obj: JsonObject) = Expense(
        paidBy = shares(obj["paid_by"]!!.jsonObject),
        equalBy = false,
        paidFor = shares(obj["paid_for"]!!.jsonObject),
        equalFor = false,
        date = "2026-09-17",
        type = obj["type"]?.jsonPrimitive?.content,
    )

    @Test
    fun `balances match the shared case table, and always sum to zero`() {
        for (case in group("balances")) {
            val name = case["name"]!!.jsonPrimitive.content
            val expenses = case["expenses"]!!.jsonArray.map { expenseOf(it.jsonObject) }
            val participants = case["participants"]!!.jsonArray.map { it.jsonPrimitive.content }
            val balances = ExpenseMath.balancesFor(expenses, participants)

            val rendered = balances.associate { balance ->
                balance.accountId to mapOf(
                    "paid" to ExpenseMath.fromCents(balance.paidCents),
                    "share" to ExpenseMath.fromCents(balance.shareCents),
                    "settled" to ExpenseMath.fromCents(balance.settledCents),
                    "balance" to ExpenseMath.fromCents(balance.balanceCents),
                )
            }
            val expected = case["expect"]!!.jsonObject.mapValues { (_, value) ->
                value.jsonObject.mapValues { (_, amount) -> amount.jsonPrimitive.content }
            }
            assertEquals(name, expected, rendered)
            // The table lists people in the order balancesFor must return them: largest credit
            // first, then by plain id comparison rather than any locale's collation (T-204).
            assertEquals(name, expected.keys.toList(), balances.map { it.accountId })

            // The property that makes the screen trustworthy: nothing is owed to nobody.
            assertEquals(name, 0L, balances.sumOf { it.balanceCents })
            // The table's total is the GROSS sum of every entry, all three types included.
            assertEquals(
                name,
                cents(case["total"]!!.jsonPrimitive.content),
                expenses.sumOf { ExpenseMath.expenseTotalCents(it) },
            )
            for (balance in balances) {
                // The three figures are the balance broken down, whatever mix of types made it.
                assertEquals(
                    name,
                    balance.balanceCents,
                    balance.paidCents - balance.shareCents + balance.settledCents,
                )
                // And a balance is exactly what every entry did to that account, one by one.
                assertEquals(
                    name,
                    balance.balanceCents,
                    expenses.sumOf { ExpenseMath.entryEffectCents(it, balance.accountId) },
                )
            }
        }
    }

    @Test
    fun `net spent matches the shared case table`() {
        for (case in group("spent")) {
            val name = case["name"]!!.jsonPrimitive.content
            val totals = ExpenseMath.spentTotals(case["expenses"]!!.jsonArray.map { expenseOf(it.jsonObject) })
            val rendered = mapOf(
                "expenses" to ExpenseMath.fromCents(totals.expensesCents),
                "income" to ExpenseMath.fromCents(totals.incomeCents),
                "net" to ExpenseMath.fromCents(totals.netCents),
            )
            assertEquals(name, shares(case["expect"]!!.jsonObject), rendered)
        }
    }

    @Test
    fun `what one entry does to one account matches the shared case table`() {
        for (case in group("effect")) {
            val name = case["name"]!!.jsonPrimitive.content
            val expense = expenseOf(case["expense"]!!.jsonObject)
            val account = case["account"]!!.jsonPrimitive.content
            assertEquals(
                name,
                case["expect"]!!.jsonPrimitive.content,
                ExpenseMath.fromCents(ExpenseMath.entryEffectCents(expense, account)),
            )
        }
    }

    @Test
    fun `settling up matches the shared case table, and zeroes every balance`() {
        // Balances in the table are signed; the wire amount format is not, so the sign is peeled off.
        fun signedCents(amount: String): Long =
            if (amount.startsWith("-")) -cents(amount.substring(1)) else cents(amount)

        for (case in group("settle")) {
            val name = case["name"]!!.jsonPrimitive.content
            val balances = case["balances"]!!.jsonObject.map { (id, amount) ->
                val balance = signedCents(amount.jsonPrimitive.content)
                ExpenseMath.Balance(id, paidCents = balance, shareCents = 0, balanceCents = balance)
            }
            val transfers = ExpenseMath.settle(balances)

            val rendered = transfers.map {
                mapOf("from" to it.from, "to" to it.to, "amount" to ExpenseMath.fromCents(it.cents))
            }
            val expected = case["expect"]!!.jsonArray.map { transfer ->
                transfer.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
            }
            assertEquals(name, expected, rendered)

            // The properties that make the section trustworthy: every transfer is a real payment,
            // together they leave nobody owing anything, and there are never more than n-1 of them.
            val remaining = balances.associate { it.accountId to it.balanceCents }.toMutableMap()
            for (transfer in transfers) {
                assertTrue(name, transfer.cents > 0)
                remaining[transfer.from] = remaining.getValue(transfer.from) + transfer.cents
                remaining[transfer.to] = remaining.getValue(transfer.to) - transfer.cents
            }
            assertTrue(name, remaining.values.all { it == 0L })
            val withBalance = balances.count { it.balanceCents != 0L }
            assertTrue(name, transfers.size <= maxOf(withBalance - 1, 0))
        }
    }

    @Test
    fun `the case table covers every group this test drives`() {
        // A renamed or dropped group would otherwise make a whole block silently iterate nothing.
        for (name in listOf(
            "to_cents", "from_cents", "equal_split", "distribute", "balances", "spent", "effect", "settle",
        )) {
            assertTrue(name, group(name).isNotEmpty())
        }
    }

    // ---- properties and the cases only this client needs ---------------------

    @Test
    fun `no split ever loses or invents a cent`() {
        for (total in 1L..400L) {
            for (people in 1..7) {
                val ids = (0 until people).map { "p$it" }
                val result = ExpenseMath.distribute(total, ids.map { ExpenseMath.ShareEntry(it) })
                val shares = (result as ExpenseMath.DistributeResult.Shares).shares
                assertEquals("$total among $people", total, shares.values.sum())
                assertTrue("$total among $people", shares.values.all { it > 0 })
            }
        }
    }

    @Test
    fun `former members are numbered by first appearance, and members never are`() {
        fun expense(vararg ids: String) = Expense(
            paidBy = mapOf(ids.first() to "1.00"),
            equalBy = false,
            paidFor = ids.associateWith { "1.00" },
            equalFor = false,
            date = "2026-09-17",
        )

        val numbers = ExpenseMath.formerMemberNumbers(
            listOf(expense("a", "gone-one"), expense("a", "gone-two")),
            setOf("a"),
        )

        assertEquals(mapOf("gone-one" to 1, "gone-two" to 2), numbers)
    }

    @Test
    fun `a member who has spent nothing still has a balance of zero`() {
        val balances = ExpenseMath.balancesFor(emptyList(), listOf("a", "b"))

        assertEquals(listOf("a", "b"), balances.map { it.accountId })
        assertTrue(balances.all { it.balanceCents == 0L })
    }

    // ---- entry types (T-245) -------------------------------------------------

    private fun typed(type: String?) = Expense(
        paidBy = mapOf("a" to "10.00"),
        equalBy = true,
        paidFor = mapOf("b" to "10.00"),
        equalFor = true,
        date = "2026-09-17",
        type = type,
    )

    @Test
    fun `the three types are read as themselves`() {
        assertEquals(ExpenseType.EXPENSE, ExpenseMath.entryType(typed("expense")))
        assertEquals(ExpenseType.INCOME, ExpenseMath.entryType(typed("income")))
        assertEquals(ExpenseType.TRANSFER, ExpenseMath.entryType(typed("transfer")))
    }

    @Test
    fun `an absent type is an expense — every entry written before types existed is one`() {
        assertEquals(ExpenseType.EXPENSE, ExpenseMath.entryType(typed(null)))
    }

    @Test
    fun `a word this client does not know is an expense rather than a dropped entry`() {
        // The server refuses these, so one arriving is a newer client's vocabulary; the screen
        // still has to add up.
        assertEquals(ExpenseType.EXPENSE, ExpenseMath.entryType(typed("Income")))
        assertEquals(ExpenseType.EXPENSE, ExpenseMath.entryType(typed("refund")))
    }

    @Test
    fun `a settlement stays out of what people paid and consumed`() {
        val transfer = Expense(
            paidBy = mapOf("b" to "15.00"),
            equalBy = true,
            paidFor = mapOf("a" to "15.00"),
            equalFor = true,
            date = "2026-09-17",
            type = "transfer",
        )

        val balances = ExpenseMath.balancesFor(listOf(transfer), listOf("a", "b"))

        assertEquals(
            listOf(
                ExpenseMath.Balance("b", paidCents = 0, shareCents = 0, settledCents = 1500, balanceCents = 1500),
                ExpenseMath.Balance("a", paidCents = 0, shareCents = 0, settledCents = -1500, balanceCents = -1500),
            ),
            balances,
        )
    }

    @Test
    fun `a ledger of random entries always adds up (seeded property test)`() {
        // A seeded generator: the same ledgers every run, so a failure can be reproduced. xorshift32,
        // as the web's property test uses — small, deterministic and identical on every engine.
        var state = 20260920
        fun random(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
        fun pick(n: Int): Int = (random() * n).toInt()

        repeat(300) {
            val people = 2 + pick(4)
            val ids = (0 until people).map { index -> "p$index" }
            val expenses = mutableListOf<Expense>()
            repeat(1 + pick(6)) {
                val type = ExpenseType.entries[pick(3)]
                val totalCents = (1 + pick(20000)).toLong()
                if (type == ExpenseType.TRANSFER) {
                    val from = pick(people)
                    val to = (from + 1 + pick(people - 1)) % people
                    expenses += Expense(
                        paidBy = mapOf(ids[from] to ExpenseMath.fromCents(totalCents)),
                        equalBy = true,
                        paidFor = mapOf(ids[to] to ExpenseMath.fromCents(totalCents)),
                        equalFor = true,
                        date = "2026-09-17",
                        type = type.wire,
                    )
                    return@repeat
                }
                val payers = ids.filter { random() < 0.5 }.ifEmpty { listOf(ids[pick(people)]) }
                val forWhom = ids.filter { random() < 0.7 }.ifEmpty { listOf(ids[pick(people)]) }
                expenses += Expense(
                    paidBy = ExpenseMath.sharesToWire(ExpenseMath.splitEqually(totalCents, payers)),
                    equalBy = true,
                    paidFor = ExpenseMath.sharesToWire(ExpenseMath.splitEqually(totalCents, forWhom)),
                    equalFor = true,
                    date = "2026-09-17",
                    type = type.wire,
                )
            }

            val balances = ExpenseMath.balancesFor(expenses, ids)
            assertEquals(0L, balances.sumOf { balance -> balance.balanceCents })
            for (balance in balances) {
                assertEquals(
                    balance.balanceCents,
                    balance.paidCents - balance.shareCents + balance.settledCents,
                )
            }

            // Settling the suggested transfers really does leave everyone square.
            val remaining = balances.associate { it.accountId to it.balanceCents }.toMutableMap()
            for (transfer in ExpenseMath.settle(balances)) {
                assertTrue(transfer.cents > 0)
                remaining[transfer.from] = remaining.getValue(transfer.from) + transfer.cents
                remaining[transfer.to] = remaining.getValue(transfer.to) - transfer.cents
            }
            assertTrue(remaining.values.all { cents -> cents == 0L })

            val totals = ExpenseMath.spentTotals(expenses)
            assertEquals(totals.expensesCents - totals.incomeCents, totals.netCents)
            val gross = expenses
                .filter { ExpenseMath.entryType(it) != ExpenseType.TRANSFER }
                .sumOf { ExpenseMath.expenseTotalCents(it) }
            assertEquals(gross, totals.expensesCents + totals.incomeCents)
        }
    }
}
