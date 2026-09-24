package org.p23q.shoppinglist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.p23q.shoppinglist.core.DeviceIdProvider

@Module
@InstallIn(SingletonComponent::class)
object ServerConfigModule {
    @Provides
    @Singleton
    fun provideServerConfigDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("server_config") }
}

/**
 * This device's id, minted once per install. The server address used to live here too; it is per
 * account now ([org.p23q.shoppinglist.core.db.AccountEntity.serverUrl]), and the old keys are read
 * once by the schema-9 migration and then deleted ([discardLegacy]).
 */
@Singleton
class ServerConfig @Inject constructor(private val dataStore: DataStore<Preferences>) : DeviceIdProvider {
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

    override suspend fun get(): String = deviceId()

    /** The single-session app's server URL, for the schema-9 migration. */
    internal suspend fun legacyServerUrl(): String? = dataStore.data.first()[SERVER_URL_KEY]

    internal suspend fun legacyAllowSelfSignedCerts(): Boolean = dataStore.data.first()[ALLOW_SELF_SIGNED_KEY] ?: false

    internal suspend fun discardLegacy() {
        dataStore.edit {
            it.remove(SERVER_URL_KEY)
            it.remove(ALLOW_SELF_SIGNED_KEY)
        }
    }

    internal companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        val ALLOW_SELF_SIGNED_KEY = booleanPreferencesKey("allow_self_signed_certs")
    }
}
