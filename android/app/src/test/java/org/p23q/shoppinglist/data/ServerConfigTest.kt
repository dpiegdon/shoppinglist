package org.p23q.shoppinglist.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerConfigTest {

    private fun newServerConfig(): ServerConfig {
        val file = File.createTempFile("server_config_test", ".preferences_pb")
        file.deleteOnExit()
        return ServerConfig(PreferenceDataStoreFactory.create { file })
    }

    @Test
    fun `server URL is normalized to always end in a slash`() = runTest {
        val config = newServerConfig()
        config.setServerUrl("https://example.com/sub")
        assertEquals("https://example.com/sub/", config.serverUrl.first())
    }

    @Test
    fun `allowSelfSignedCerts defaults to false`() = runTest {
        assertFalse(newServerConfig().allowSelfSignedCerts.first())
    }

    @Test
    fun `allowSelfSignedCerts persists once set`() = runTest {
        val config = newServerConfig()
        config.setAllowSelfSignedCerts(true)
        assertTrue(config.allowSelfSignedCerts.first())
        config.setAllowSelfSignedCerts(false)
        assertFalse(config.allowSelfSignedCerts.first())
    }
}
