package org.p23q.shoppinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-110 list-kind rules. Must stay in lockstep with the web client's listKind.test.ts — the two
 * clients share the rule (client-ui-notes.md) so they render the same synced list identically.
 */
class ListKindTest {

    @Test
    fun `absent or unknown kinds fall back to shopping`() {
        // Pre-T-110 rows and older servers send no kind; a newer client could send one we don't know.
        assertEquals(ListKind.SHOPPING, ListKind.of(null))
        assertEquals(ListKind.SHOPPING, ListKind.of(""))
        assertEquals(ListKind.SHOPPING, ListKind.of("tasks"))
        assertEquals(ListKind.SHOPPING, ListKind.DEFAULT)
    }

    @Test
    fun `explicit kinds round-trip`() {
        assertEquals(ListKind.CHECKLIST, ListKind.of("checklist"))
        assertEquals(ListKind.SHOPPING, ListKind.of("shopping"))
    }

    @Test
    fun `only shopping lists show the shopping-only fields`() {
        assertTrue(ListKind.showsShoppingFields(ListKind.SHOPPING))
        assertTrue(ListKind.showsShoppingFields(null)) // default stays a shopping list
        assertFalse(ListKind.showsShoppingFields(ListKind.CHECKLIST))
    }

    @Test
    fun `labels and icons distinguish the two kinds`() {
        assertEquals("Shopping list", ListKind.label(ListKind.SHOPPING))
        assertEquals("Checklist", ListKind.label(ListKind.CHECKLIST))
        assertTrue(ListKind.icon(ListKind.SHOPPING) != ListKind.icon(ListKind.CHECKLIST))
    }
}
