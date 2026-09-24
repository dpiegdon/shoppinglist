package org.p23q.shoppinglist.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-108 category identity/casing rules. These MUST stay in lockstep with the web client's
 * categories.test.ts — the two clients share the rule (client-ui-notes.md) so they group the same
 * synced data identically. The tie-break and autocomplete-order cases (T-274) are driven by the
 * shared table this shares with the web suite: shared-test-cases/category-canon.json.
 */
class CategoryCanonTest {

    private val cases: JsonObject = run {
        // Unit tests run from android/app, so the repo root is two levels up (as ExpenseMathTest).
        val file = File("../../shared-test-cases/category-canon.json")
        assertTrue("missing shared case table at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun group(name: String) = cases[name]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `key is case-insensitive and trimmed`() {
        assertEquals("group", CategoryCanon.key("  Group "))
        assertEquals(CategoryCanon.key("GROUP"), CategoryCanon.key("group"))
        assertEquals("", CategoryCanon.key("   "))
    }

    @Test
    fun `canonical name is the most-frequent casing`() {
        val names = CategoryCanon.canonicalNames(listOf("group", "group", "Group"), emptyList())
        assertEquals("group", names["group"])
    }

    @Test
    fun `category_order casing overrides the item casing`() {
        val names = CategoryCanon.canonicalNames(listOf("group", "group"), listOf("Group"))
        assertEquals("Group", names["group"])
    }

    @Test
    fun `distinctCanonical returns one sorted entry per case-insensitive category`() {
        assertEquals(
            listOf("Bakery", "Dairy"),
            CategoryCanon.distinctCanonical(listOf("Dairy", "Dairy", "dairy", "Bakery"), emptyList()),
        )
    }

    @Test
    fun `planRename targets the whole category, skipping items already at the target casing`() {
        val items = listOf("1" to "group", "2" to "Group", "3" to "Other")
        val plan = CategoryCanon.planRename(items, emptyList(), "group", "Group")
        assertEquals(listOf("1"), plan.itemIds) // "2" already "Group"
    }

    @Test
    fun `planRename rewrites the matching order entry`() {
        val items = listOf("1" to "group")
        val plan = CategoryCanon.planRename(items, listOf("group", "Other"), "group", "Groceries")
        assertEquals(listOf("Groceries", "Other"), plan.nextCategoryOrder)
        assertTrue(plan.orderChanged)
    }

    @Test
    fun `planRename de-duplicates a collision as a merge`() {
        val items = listOf("1" to "group", "2" to "Group")
        val plan = CategoryCanon.planRename(items, listOf("group", "Other"), "group", "other")
        assertEquals(listOf("other"), plan.nextCategoryOrder) // "Other" dropped as a dup
        assertTrue(plan.orderChanged)
        assertEquals(setOf("1", "2"), plan.itemIds.toSet())
    }

    @Test
    fun `planRename leaves an unordered category out of the order`() {
        val plan = CategoryCanon.planRename(listOf("1" to "group"), listOf("Other"), "group", "Group")
        assertEquals(listOf("Other"), plan.nextCategoryOrder)
        assertFalse(plan.orderChanged)
    }

    @Test
    fun `normalizeOrder drops blanks and collapses a case-insensitive duplicate onto the first (T-212)`() {
        assertEquals(
            listOf("Dairy", "Bread"),
            CategoryCanon.normalizeOrder(listOf("Dairy", " ", "dairy", "Bread", "bread ")),
        )
    }

    // ---- shared table (T-274) -------------------------------------------------

    @Test
    fun `canonical names match the shared table, tie-break included`() {
        for (case in group("canonical_names")) {
            val name = case["name"]!!.jsonPrimitive.content
            val raw = case["raw_categories"]!!.jsonArray.map { it.jsonPrimitive.content }
            val order = case["category_order"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expected = case["expect"]!!.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
            assertEquals(name, expected, CategoryCanon.canonicalNames(raw, order))
        }
    }

    @Test
    fun `autocomplete order matches the shared table`() {
        for (case in group("autocomplete_order")) {
            val name = case["name"]!!.jsonPrimitive.content
            val raw = case["raw_categories"]!!.jsonArray.map { it.jsonPrimitive.content }
            val order = case["category_order"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expected = case["expect"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(name, expected, CategoryCanon.distinctCanonical(raw, order))
        }
    }

    @Test
    fun `the case table covers every group this test drives`() {
        for (name in listOf("canonical_names", "autocomplete_order")) {
            assertTrue(name, group(name).isNotEmpty())
        }
    }
}
