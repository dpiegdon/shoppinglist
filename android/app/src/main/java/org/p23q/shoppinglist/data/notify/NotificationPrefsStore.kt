package org.p23q.shoppinglist.data.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
annotation class NotificationPrefsDataStore

@Module
@InstallIn(SingletonComponent::class)
object NotificationPrefsModule {
    @Provides
    @Singleton
    @NotificationPrefsDataStore
    fun provideNotificationPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("notification_prefs") }
}

/**
 * Collaborator-change notification preferences (T-65): a global on/off plus per-list mutes.
 * Client-only, device-local (like [org.p23q.shoppinglist.data.ThemePreferenceStore]) — muting a
 * list on your phone shouldn't mute it on your tablet, so this is deliberately NOT server-synced,
 * and survives logout.
 */
@Singleton
class NotificationPrefsStore @Inject constructor(
    @param:NotificationPrefsDataStore private val dataStore: DataStore<Preferences>,
) {
    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { it[ENABLED_KEY] ?: true }

    val mutedListIds: Flow<Set<String>> = dataStore.data.map { it[MUTED_LISTS_KEY] ?: emptySet() }

    /**
     * Whether the app has already prompted for POST_NOTIFICATIONS (T-72). The in-app toggle
     * defaults on, so the toggle's own flip-to-enable request never fires on a fresh install —
     * MainActivity asks once after login instead, guarded by this so it prompts at most once.
     */
    val notificationPermissionRequested: Flow<Boolean> =
        dataStore.data.map { it[PERMISSION_REQUESTED_KEY] ?: false }

    /**
     * Wall-clock time of the last background (WorkManager) sync attempt, or 0 if none since install
     * (T-112). Surfaced in Settings → Diagnostics so the user can confirm on-device whether
     * background sync is actually running — if it stays 0/old while the app is closed, the OS is
     * likely killing background work, which is also why notifications wouldn't fire.
     */
    val lastBackgroundSyncAt: Flow<Long> = dataStore.data.map { it[LAST_BG_SYNC_KEY] ?: 0L }

    suspend fun recordBackgroundSync(atMillis: Long) {
        dataStore.edit { it[LAST_BG_SYNC_KEY] = atMillis }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setNotificationPermissionRequested(requested: Boolean) {
        dataStore.edit { it[PERMISSION_REQUESTED_KEY] = requested }
    }

    suspend fun setListMuted(listId: String, muted: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[MUTED_LISTS_KEY] ?: emptySet()
            prefs[MUTED_LISTS_KEY] = if (muted) current + listId else current - listId
        }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("notifications_enabled")
        val MUTED_LISTS_KEY = stringSetPreferencesKey("muted_list_ids")
        val PERMISSION_REQUESTED_KEY = booleanPreferencesKey("notification_permission_requested")
        val LAST_BG_SYNC_KEY = longPreferencesKey("last_background_sync_at")
    }
}
