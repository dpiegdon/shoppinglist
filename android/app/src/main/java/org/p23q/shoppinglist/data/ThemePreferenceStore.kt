package org.p23q.shoppinglist.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Notes: theme preference is client-only, not server-synced — so it must survive logout, and it
 * belongs to the device, not to any one account.
 */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

class ThemePreferenceStore(
    private val dataStore: DataStore<Preferences>,
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
