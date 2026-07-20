package org.p23q.shoppinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-108 category identity/casing rules. These MUST stay in lockstep with the web client's
 * categories.test.ts — the two clients share the rule (client-ui-notes.md) so they group the same
 * synced data identically.
 */
class CategoryCanonTest {

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
}
