package org.p23q.shoppinglist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {

    @Test
    fun `route patterns declare the listId argument placeholder`() {
        assertEquals("list/{listId}", Routes.LIST_PATTERN)
        assertEquals("registry/{listId}", Routes.REGISTRY_PATTERN)
        assertEquals("listProps/{listId}", Routes.LIST_PROPS_PATTERN)
    }

    @Test
    fun `static routes have no arguments`() {
        assertEquals("login", Routes.LOGIN)
        assertEquals("overview", Routes.OVERVIEW)
        assertEquals("settings", Routes.SETTINGS)
    }

    @Test
    fun `list builds a concrete route for a given listId`() {
        assertEquals("list/abc-123", Routes.list("abc-123"))
    }

    @Test
    fun `registry builds a concrete route for a given listId`() {
        assertEquals("registry/abc-123", Routes.registry("abc-123"))
    }

    @Test
    fun `listProps builds a concrete route for a given listId`() {
        assertEquals("listProps/abc-123", Routes.listProps("abc-123"))
    }
}
