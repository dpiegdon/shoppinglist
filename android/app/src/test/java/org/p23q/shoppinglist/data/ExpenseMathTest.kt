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

    @Test
    fun `balances match the shared case table, and always sum to zero`() {
        for (case in group("balances")) {
            val name = case["name"]!!.jsonPrimitive.content
            val expenses = case["expenses"]!!.jsonArray.map { element ->
                val obj = element.jsonObject
                Expense(
                    paidBy = shares(obj["paid_by"]!!.jsonObject),
                    equalBy = false,
                    paidFor = shares(obj["paid_for"]!!.jsonObject),
                    equalFor = false,
                    date = "2026-09-17",
                )
            }
            val participants = case["participants"]!!.jsonArray.map { it.jsonPrimitive.content }
            val balances = ExpenseMath.balancesFor(expenses, participants)

            val rendered = balances.associate { balance ->
                balance.accountId to mapOf(
                    "paid" to ExpenseMath.fromCents(balance.paidCents),
                    "share" to ExpenseMath.fromCents(balance.shareCents),
                    "balance" to ExpenseMath.fromCents(balance.balanceCents),
                )
            }
            val expected = case["expect"]!!.jsonObject.mapValues { (_, value) ->
                value.jsonObject.mapValues { (_, amount) -> amount.jsonPrimitive.content }
            }
            assertEquals(name, expected, rendered)

            // The property that makes the screen trustworthy: nothing is owed to nobody.
            assertEquals(name, 0L, balances.sumOf { it.balanceCents })
            assertEquals(
                name,
                cents(case["total"]!!.jsonPrimitive.content),
                expenses.sumOf { ExpenseMath.expenseTotalCents(it) },
            )
        }
    }

    @Test
    fun `the case table covers every group this test drives`() {
        // A renamed or dropped group would otherwise make a whole block silently iterate nothing.
        for (name in listOf("to_cents", "from_cents", "equal_split", "distribute", "balances")) {
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
}
