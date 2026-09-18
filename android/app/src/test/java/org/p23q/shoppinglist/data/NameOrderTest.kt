package org.p23q.shoppinglist.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared name order (T-176), driven by the table the web's nameOrder.test.ts reads too: the
 * point is that both clients agree, so the cases live in one place.
 */
class NameOrderTest {

    private val cases: JsonObject = run {
        // Unit tests run from android/app, so the repo root is two levels up (as ExpenseMathTest).
        val file = File("../../shared-test-cases/name-order.json")
        assertTrue("missing shared case table at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun group(name: String) = cases[name]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `keys match the shared table`() {
        val keys = group("keys")
        assertTrue(keys.isNotEmpty())
        for (case in keys) {
            val name = case["name"]!!.jsonPrimitive.content
            assertEquals(name, case["key"]!!.jsonPrimitive.content, NameOrder.key(name))
        }
    }

    @Test
    fun `orders match the shared table`() {
        val orders = group("orders")
        assertTrue(orders.isNotEmpty())
        for (case in orders) {
            val lists = case["lists"]!!.jsonArray.map { it.jsonObject }
                .map { it["id"]!!.jsonPrimitive.content to it["name"]!!.jsonPrimitive.content }
            val sorted = lists.sortedWith(NameOrder.by({ it.second }, { it.first })).map { it.first }
            val expected = case["expect"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(case["name"]!!.jsonPrimitive.content, expected, sorted)
        }
    }
}
