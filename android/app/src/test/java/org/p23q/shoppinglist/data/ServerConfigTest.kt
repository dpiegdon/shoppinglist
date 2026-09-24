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
        assertEquals(first, config.get())
    }

    @Test
    fun `the single-session server keys are readable for the migration, then discarded (T-291)`() = runTest {
        val store = newDataStore()
        store.edit {
            it[ServerConfig.SERVER_URL_KEY] = "https://example.com/sub/"
            it[ServerConfig.ALLOW_SELF_SIGNED_KEY] = true
        }
        val config = ServerConfig(store)
        val deviceId = config.deviceId()
        assertEquals("https://example.com/sub/", config.legacyServerUrl())
        assertTrue(config.legacyAllowSelfSignedCerts())

        config.discardLegacy()

        assertNull(config.legacyServerUrl())
        assertFalse(config.legacyAllowSelfSignedCerts())
        // The device id is not a single-session key: it stays global.
        assertEquals(deviceId, config.deviceId())
    }
}
