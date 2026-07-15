package org.p23q.shoppinglist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
annotation class ShowCheckedDataStore

@Module
@InstallIn(SingletonComponent::class)
object ShowCheckedModule {
    @Provides
    @Singleton
    @ShowCheckedDataStore
    fun provideShowCheckedDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("show_checked") }
}

/**
 * The list screen's "show checked" toggle, remembered app-wide (not per list) across list opens
 * and app restarts. Client-only and device-local — a view preference, never synced to the server,
 * so like [ThemePreferenceStore] it survives logout. Default: checked items hidden.
 */
@Singleton
class ShowCheckedStore @Inject constructor(
    @param:ShowCheckedDataStore private val dataStore: DataStore<Preferences>,
) {
    val showChecked: Flow<Boolean> = dataStore.data.map { it[SHOW_CHECKED_KEY] ?: false }

    suspend fun setShowChecked(value: Boolean) {
        dataStore.edit { it[SHOW_CHECKED_KEY] = value }
    }

    private companion object {
        val SHOW_CHECKED_KEY = booleanPreferencesKey("show_checked")
    }
}
