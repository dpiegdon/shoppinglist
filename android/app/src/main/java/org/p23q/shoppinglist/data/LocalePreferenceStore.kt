package org.p23q.shoppinglist.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalePreferenceDataStore

@Module
@InstallIn(SingletonComponent::class)
object LocalePreferenceModule {
    @Provides
    @Singleton
    @LocalePreferenceDataStore
    fun provideLocalePreferenceDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("locale_preference") }
}

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
@Singleton
class LocalePreferenceStore @Inject constructor(
    @param:LocalePreferenceDataStore private val dataStore: DataStore<Preferences>,
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
 * Reads the platform list rather than just `Locale.getDefault()`, so a device configured as
 * "Icelandic, then German" gets German instead of falling all the way back to English.
 */
fun deviceLocale(): AppLocale {
    val tags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val list = android.content.res.Resources.getSystem().configuration.locales
        (0 until list.size()).map { list[it].toLanguageTag() }
    } else {
        listOf(Locale.getDefault().toLanguageTag())
    }
    return AppLocale.resolve(tags)
}
