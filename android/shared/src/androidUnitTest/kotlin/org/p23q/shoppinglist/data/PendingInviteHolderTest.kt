package org.p23q.shoppinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.p23q.shoppinglist.ui.login.LoginMode

/** A parked invite belongs to the one login it was parked for (T-300). */
class PendingInviteHolderTest {

    private val holder = PendingInviteHolder()
    private val prodLink = "https://p23q.org/shopping/invite/tok-1"

    @Test
    fun `an invite parked for an added account's server resumes after signing in to it`() {
        holder.stash("tok-1", prodLink, null, LoginMode.ADD)

        // Spelled differently from the link's prefix, the same server.
        val invite = holder.consumeFor(LoginMode.ADD, "new")

        assertEquals(PendingInvite("tok-1", null, prodLink, LoginMode.ADD), invite)
    }

    @Test
    fun `an invite parked for an added account resumes whatever server the form ended up on`() {
        // The user corrected the prefilled address (an alias, or the server's real BASE_URL); a
        // token from a different server is the server's to refuse.
        holder.stash("tok-1", prodLink, null, LoginMode.ADD)

        assertEquals("tok-1", holder.consumeFor(LoginMode.ADD, "new")?.token)
    }

    @Test
    fun `an invite parked for a re-sign-in resumes only that account's`() {
        holder.stash("tok-1", prodLink, "prod", LoginMode.RESIGNIN)
        assertNull(holder.consumeFor(LoginMode.RESIGNIN, "stage"))

        holder.stash("tok-1", prodLink, "prod", LoginMode.RESIGNIN)
        assertEquals("tok-1", holder.consumeFor(LoginMode.RESIGNIN, "prod")?.token)
    }

    @Test
    fun `an invite parked for one mode does not resume a login in another`() {
        holder.stash("tok-1", null, "prod", LoginMode.RESIGNIN)

        assertNull(holder.consumeFor(LoginMode.ADD, "prod"))
    }

    @Test
    fun `a bare token parked for the first account resumes whatever server it signs in to`() {
        holder.stash("tok-1", null, null, LoginMode.START)

        assertEquals("tok-1", holder.consumeFor(LoginMode.START, "new")?.token)
    }

    @Test
    fun `a login is the invite's last chance, whether it took it or not`() {
        holder.stash("tok-1", prodLink, null, LoginMode.ADD)
        assertNull(holder.consumeFor(LoginMode.START, "new"))

        // The invite is gone: the next login does not find it either.
        assertNull(holder.consumeFor(LoginMode.ADD, "new"))
    }

    @Test
    fun `clear forgets the invite`() {
        holder.stash("tok-1", null, null, LoginMode.START)

        holder.clear()

        assertNull(holder.consumeFor(LoginMode.START, "new"))
    }
}
