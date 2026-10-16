package org.p23q.shoppinglist.core.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-327: whether an account's items can have a due date, from its server's release or a pull. */
class DueDateCapabilityTest {

    private fun server(version: String? = null, seen: Boolean = false) = AccountEntity(
        id = "a",
        serverUrl = "https://example.test/",
        accountId = "acct",
        email = null,
        label = "test",
        signedIn = true,
        serverVersion = version,
        dueDatesSeen = seen,
    )

    @Test
    fun `a server of release 3_5_0 or newer supports due dates`() {
        assertTrue(server("3.5.0").supportsDueDates)
        assertTrue(server("3.10.0").supportsDueDates)
        assertTrue(server("4.0").supportsDueDates)
    }

    @Test
    fun `an older release, or one that does not parse, does not`() {
        assertFalse(server("3.4.0").supportsDueDates)
        assertFalse(server("3.4.9").supportsDueDates)
        assertFalse(server("3.5.0-rc1").supportsDueDates)
    }

    @Test
    fun `a pulled item that carried the field unlocks them whatever the release`() {
        assertTrue(server(seen = true).supportsDueDates)
        assertTrue(server("3.4.0", seen = true).supportsDueDates)
    }

    @Test
    fun `neither a known release nor a pulled field means no due dates`() {
        assertFalse(server().supportsDueDates)
    }

    @Test
    fun `the local area syncs nowhere, so it always keeps them`() {
        val local = AccountEntity(
            id = "l", kind = AccountEntity.KIND_LOCAL, serverUrl = null, accountId = null, email = null,
            label = "", signedIn = false,
        )
        assertTrue(local.supportsDueDates)
    }
}
