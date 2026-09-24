package org.p23q.shoppinglist.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.p23q.shoppinglist.core.db.AccountEntity

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
        assertEquals("about", Routes.ABOUT)
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

    @Test
    fun `authedStartDestination opens the last-opened list when one is remembered`() {
        assertEquals("list/abc-123", authedStartDestination("abc-123"))
    }

    @Test
    fun `authedStartDestination falls back to the overview when no list was remembered`() {
        assertEquals(Routes.OVERVIEW, authedStartDestination(null))
    }

    private fun account(id: String, kind: String = AccountEntity.KIND_SERVER, signedIn: Boolean = true) = AccountEntity(
        id = id,
        kind = kind,
        serverUrl = if (kind == AccountEntity.KIND_SERVER) "https://lists.example.test/" else null,
        accountId = if (kind == AccountEntity.KIND_SERVER) "acct-$id" else null,
        email = "me@example.com",
        label = "test",
        signedIn = signedIn,
    )

    @Test
    fun `a cold start with no account opens the start screen`() {
        assertEquals(Routes.LOGIN, coldStartDestination(emptyList(), notifiedListId = "l1", lastOpenedListId = "l2"))
    }

    @Test
    fun `a cold start with only a local account still opens the start screen`() {
        val local = account("local", kind = AccountEntity.KIND_LOCAL)
        assertEquals(Routes.LOGIN, coldStartDestination(listOf(local), notifiedListId = null, lastOpenedListId = null))
    }

    @Test
    fun `a cold start with every account signed out opens the lists, not the start screen (T-292)`() {
        val signedOut = listOf(account("a", signedIn = false), account("b", signedIn = false))
        assertEquals(Routes.OVERVIEW, coldStartDestination(signedOut, notifiedListId = null, lastOpenedListId = null))
        assertEquals(Routes.list("l2"), coldStartDestination(signedOut, notifiedListId = null, lastOpenedListId = "l2"))
    }

    @Test
    fun `a cold start from a notification opens its list`() {
        assertEquals(Routes.list("l1"), coldStartDestination(listOf(account("a")), notifiedListId = "l1", lastOpenedListId = "l2"))
    }
}
