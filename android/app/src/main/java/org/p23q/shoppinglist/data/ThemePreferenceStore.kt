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
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Notes: theme preference is client-only, not server-synced — so it must survive logout, unlike
 * [SessionState] (cleared on logout) or [ServerConfig] (one server per install, not per account).
 */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ThemePreferenceDataStore

@Module
@InstallIn(SingletonComponent::class)
object ThemePreferenceModule {
    @Provides
    @Singleton
    @ThemePreferenceDataStore
    fun provideThemePreferenceDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("theme_preference") }
}

@Singleton
class ThemePreferenceStore @Inject constructor(
    @param:ThemePreferenceDataStore private val dataStore: DataStore<Preferences>,
) {
    val theme: Flow<ThemePreference> = dataStore.data.map { prefs ->
        prefs[THEME_KEY]?.let { stored -> runCatching { ThemePreference.valueOf(stored) }.getOrNull() }
            ?: ThemePreference.SYSTEM
    }

    suspend fun setTheme(preference: ThemePreference) {
        dataStore.edit { it[THEME_KEY] = preference.name }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preference")
    }
}
