package org.p23q.shoppinglist.data

import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * On the simulator's Keychain and user defaults, under a service and a suite of their own.
 *
 * The Keychain exists only inside an app's sandbox: the bare test binary `./gradlew :shared:iosX64Test`
 * spawns on the simulator gets errSecNotAvailable (-25291) from every Security call, so the token
 * tests skip there (printed, not hidden) and the app's own login is what exercises the store.
 * They run when the tests are hosted by an app (XCTest from the iOS shell, T-353).
 */
class KeychainSecretStoreTest {

    private val defaults = NSUserDefaults(suiteName = "org.p23q.shoppinglist.test")
    private val store = KeychainSecretStore(service = "org.p23q.shoppinglist.test.tokens", defaults = defaults)

    private val keychainAvailable: Boolean = runCatching { store.token("probe"); true }.getOrDefault(false)

    @AfterTest
    fun cleanUp() {
        if (keychainAvailable) listOf("a", "b").forEach { store.setToken(it, null) }
        defaults.removePersistentDomainForName("org.p23q.shoppinglist.test")
    }

    /** True when the test may go on; otherwise says why it does not. */
    private fun keychain(): Boolean {
        if (!keychainAvailable) println("SKIPPED: no Keychain in this process (a test binary outside an app sandbox)")
        return keychainAvailable
    }

    @Test
    fun `a token is kept per account and removed by null`() {
        if (!keychain()) return
        assertNull(store.token("a"))

        store.setToken("a", "secret-a")
        store.setToken("b", "secret-b")
        assertEquals("secret-a", store.token("a"))
        assertEquals("secret-b", store.token("b"))

        store.setToken("a", null)
        assertNull(store.token("a"))
        assertEquals("secret-b", store.token("b"))
        // Removing what is not there is not an error: a sign-out after a lost token.
        store.setToken("a", null)
    }

    @Test
    fun `setting a token again replaces it`() {
        if (!keychain()) return
        store.setToken("a", "first")
        store.setToken("a", "second")
        assertEquals("second", store.token("a"))
    }

    @Test
    fun `a token survives a new store over the same service`() {
        if (!keychain()) return
        store.setToken("a", "kept")
        val again = KeychainSecretStore(service = "org.p23q.shoppinglist.test.tokens", defaults = defaults)
        assertEquals("kept", again.token("a"))
    }

    @Test
    fun `the last opened list is kept in the defaults`() {
        assertNull(store.lastOpenedListId)
        store.lastOpenedListId = "list-1"
        assertEquals("list-1", store.lastOpenedListId)
        assertEquals("list-1", defaults.stringForKey(KeychainSecretStore.KEY_LAST_OPENED_LIST_ID))
        store.lastOpenedListId = null
        assertNull(store.lastOpenedListId)
    }
}
