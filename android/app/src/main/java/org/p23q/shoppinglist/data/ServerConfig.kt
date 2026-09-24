package org.p23q.shoppinglist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.p23q.shoppinglist.core.account.normalizeServerUrl

@Module
@InstallIn(SingletonComponent::class)
object ServerConfigModule {
    @Provides
    @Singleton
    fun provideServerConfigDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("server_config") }
}

/**
 * The server address last typed on the login screen, and its debug-only certificate opt-in: a
 * device-level convenience that no account owns (T-298). [ServerConfig] keeps it.
 */
interface LastServerAddress {
    /** The address last submitted, normalised; null before the first. */
    suspend fun lastServerUrl(): String?

    suspend fun setLastServerUrl(url: String)

    /** Debug builds only honour it; see DevCertTrust.kt. */
    suspend fun lastAllowSelfSignedCerts(): Boolean

    suspend fun setLastAllowSelfSignedCerts(allow: Boolean)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LastServerAddressModule {
    @Binds
    abstract fun bindLastServerAddress(config: ServerConfig): LastServerAddress
}

/**
 * What this device keeps outside any account: its id, minted once per install, and the server
 * address last typed on the login screen with its debug-only certificate opt-in.
 *
 * The address is a device-level convenience, not an account's: every account has its own
 * ([org.p23q.shoppinglist.core.db.AccountEntity.serverUrl]). It is saved on every submit, before
 * the server has answered, so it survives a failed attempt, a removed account and an upgrade that
 * finds an address but no session (T-298). The schema-9 migration also reads it, as the server of
 * the single-session app's account; the keys are the ones that app used.
 */
@Singleton
class ServerConfig @Inject constructor(private val dataStore: DataStore<Preferences>) : LastServerAddress {
    /** Minted once per install and persisted; stamped as `updated_by` on every field this device writes. */
    suspend fun deviceId(): String {
        dataStore.data.first()[DEVICE_ID_KEY]?.let { return it }
        val newId = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            // Re-check inside the atomic edit in case of a concurrent first call.
            if (prefs[DEVICE_ID_KEY] == null) prefs[DEVICE_ID_KEY] = newId
        }
        return dataStore.data.first()[DEVICE_ID_KEY]!!
    }

    override suspend fun lastServerUrl(): String? = dataStore.data.first()[SERVER_URL_KEY]

    override suspend fun setLastServerUrl(url: String) {
        dataStore.edit { it[SERVER_URL_KEY] = normalizeServerUrl(url) }
    }

    override suspend fun lastAllowSelfSignedCerts(): Boolean = dataStore.data.first()[ALLOW_SELF_SIGNED_KEY] ?: false

    override suspend fun setLastAllowSelfSignedCerts(allow: Boolean) {
        dataStore.edit { it[ALLOW_SELF_SIGNED_KEY] = allow }
    }

    internal companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        val ALLOW_SELF_SIGNED_KEY = booleanPreferencesKey("allow_self_signed_certs")
    }
}
