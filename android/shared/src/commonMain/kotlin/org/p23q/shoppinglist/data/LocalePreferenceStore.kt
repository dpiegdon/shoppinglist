package org.p23q.shoppinglist.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.p23q.shoppinglist.core.AppLocale

/**
 * The chosen UI language (T-111), stored device-locally like [ThemePreferenceStore].
 *
 * Device-local and NOT server-synced, deliberately: the login screen must offer a language before
 * any account exists, so a device-local value is required regardless — syncing would only add a
 * second source of truth and a conflict rule. Language is also genuinely a device property (a
 * German phone and an English work laptop is a normal arrangement), unlike currency or initials,
 * which are account-level and do sync. It therefore survives logout, like the theme.
 *
 * "No stored choice" is a real state, not a hidden default: it means "follow the device", so a
 * user who has never chosen tracks their phone's language as it changes.
 */
class LocalePreferenceStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The explicit choice, or null to follow the device. */
    val selected: Flow<AppLocale?> = dataStore.data.map { prefs ->
        prefs[LOCALE_KEY]?.let { AppLocale.match(it) }
    }

    /** The language actually in force: the explicit choice, else the device's best match. */
    val effective: Flow<AppLocale> = selected.map { it ?: deviceLocale() }

    suspend fun setLocale(locale: AppLocale?) {
        dataStore.edit { prefs ->
            if (locale == null) prefs.remove(LOCALE_KEY) else prefs[LOCALE_KEY] = locale.tag
        }
    }

    private companion object {
        val LOCALE_KEY = stringPreferencesKey("locale_preference")
    }
}

/**
 * The best shipped language for this device's ordered locale preferences.
 *
 * Reads the platform's list rather than just its first language, so a device configured as
 * "Icelandic, then German" gets German instead of falling all the way back to English.
 */
expect fun deviceLocale(): AppLocale
