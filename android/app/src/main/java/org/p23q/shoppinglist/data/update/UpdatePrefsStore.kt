package org.p23q.shoppinglist.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdatePrefsDataStore

@Module
@InstallIn(SingletonComponent::class)
object UpdatePrefsModule {
    @Provides
    @Singleton
    @UpdatePrefsDataStore
    fun provideUpdatePrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("update_prefs") }
}

/**
 * App-update preferences (T-135). Client-only and device-local, like
 * [org.p23q.shoppinglist.data.notify.NotificationPrefsStore]: whether this phone checks for
 * updates is a property of this phone, not of the account, so it is deliberately NOT
 * server-synced and it survives logout.
 */
@Singleton
class UpdatePrefsStore @Inject constructor(
    @param:UpdatePrefsDataStore private val dataStore: DataStore<Preferences>,
) {
    /** Defaults on: a self-hosted app has no store to nag you, so silence would mean never hearing. */
    val autoCheckEnabled: Flow<Boolean> = dataStore.data.map { it[AUTO_CHECK_KEY] ?: true }

    /**
     * The last version the user was actually asked about, or null if none. Asking is a one-shot
     * per version — declining is a decision about that version, and re-asking would make the
     * prompt the nuisance it exists to avoid.
     */
    val lastPromptedVersion: Flow<String?> = dataStore.data.map { it[LAST_PROMPTED_KEY] }

    /** Wall clock of the last check *attempt*, or 0 if never — the rate limit's input. */
    val lastCheckedAt: Flow<Long> = dataStore.data.map { it[LAST_CHECKED_KEY] ?: 0L }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTO_CHECK_KEY] = enabled }
    }

    suspend fun recordPrompted(version: String) {
        dataStore.edit { it[LAST_PROMPTED_KEY] = version }
    }

    suspend fun recordCheck(atMillis: Long) {
        dataStore.edit { it[LAST_CHECKED_KEY] = atMillis }
    }

    private companion object {
        val AUTO_CHECK_KEY = booleanPreferencesKey("auto_check_enabled")
        val LAST_PROMPTED_KEY = stringPreferencesKey("last_prompted_version")
        val LAST_CHECKED_KEY = longPreferencesKey("last_checked_at")
    }
}
