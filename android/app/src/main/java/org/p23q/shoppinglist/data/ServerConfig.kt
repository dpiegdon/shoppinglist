package org.p23q.shoppinglist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServerConfigModule {
    @Provides
    @Singleton
    fun provideServerConfigDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("server_config") }
}

/**
 * Self-hosted server address (user-configurable, no fixed public URL — Notes) plus this device's
 * id. The server runs as a Flask blueprint and may be mounted under an arbitrary path (e.g.
 * https://host/my/stuff/shoppinglist/), so [serverUrl] is the full prefix the user enters,
 * normalized to always end in '/'; [Api] paths are relative and resolve underneath it.
 */
@Singleton
class ServerConfig @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val serverUrl: Flow<String?> = dataStore.data.map { it[SERVER_URL_KEY] }

    suspend fun setServerUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        dataStore.edit { it[SERVER_URL_KEY] = normalized }
    }

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

    private companion object {
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    }
}
