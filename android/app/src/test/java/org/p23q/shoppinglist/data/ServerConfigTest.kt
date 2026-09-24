package org.p23q.shoppinglist.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerConfigTest {

    private fun newDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("server_config_test", ".preferences_pb")
        file.deleteOnExit()
        return PreferenceDataStoreFactory.create { file }
    }

    @Test
    fun `the device id is minted once and then kept`() = runTest {
        val config = ServerConfig(newDataStore())
        val first = config.deviceId()
        assertEquals(first, config.deviceId())
    }

    @Test
    fun `the last-typed server is the single-session app's, and nothing discards it (T-298)`() = runTest {
        val store = newDataStore()
        store.edit {
            it[ServerConfig.SERVER_URL_KEY] = "https://example.com/sub/"
            it[ServerConfig.ALLOW_SELF_SIGNED_KEY] = true
        }
        val config = ServerConfig(store)

        // What 3.1.0 stored is what the login screen prefills, and what the migration reads.
        assertEquals("https://example.com/sub/", config.lastServerUrl())
        assertTrue(config.lastAllowSelfSignedCerts())
    }

    @Test
    fun `a submitted address is stored normalised`() = runTest {
        val config = ServerConfig(newDataStore())
        assertNull(config.lastServerUrl())
        assertFalse(config.lastAllowSelfSignedCerts())

        config.setLastServerUrl("HTTPS://Lists.Example.com:443/shop")
        config.setLastAllowSelfSignedCerts(true)

        assertEquals("https://lists.example.com/shop/", config.lastServerUrl())
        assertTrue(config.lastAllowSelfSignedCerts())
    }
}
