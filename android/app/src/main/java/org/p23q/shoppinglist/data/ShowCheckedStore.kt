package org.p23q.shoppinglist.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The list screen's "show checked" toggle, remembered app-wide (not per list) across list opens
 * and app restarts. Client-only and device-local — a view preference, never synced to the server,
 * so like [ThemePreferenceStore] it survives logout. Default: checked items hidden.
 */
class ShowCheckedStore(
    private val dataStore: DataStore<Preferences>,
) {
    val showChecked: Flow<Boolean> = dataStore.data.map { it[SHOW_CHECKED_KEY] ?: false }

    suspend fun setShowChecked(value: Boolean) {
        dataStore.edit { it[SHOW_CHECKED_KEY] = value }
    }

    private companion object {
        val SHOW_CHECKED_KEY = booleanPreferencesKey("show_checked")
    }
}
