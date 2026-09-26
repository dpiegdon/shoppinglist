package org.p23q.shoppinglist.data.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.p23q.shoppinglist.core.sync.ChangeCheckOutcome
import org.p23q.shoppinglist.core.sync.InviteCheckOutcome
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

/** The last collaborator-change check (T-318): when, how many foreign items, how it ended. */
data class ChangeCheck(val atMillis: Long, val foreignItems: Int, val outcome: ChangeCheckOutcome)

/** The last invite check (T-319): when, how many invites were new to this phone, how it ended. */
data class InviteCheck(val atMillis: Long, val newInvites: Int, val outcome: InviteCheckOutcome)

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

    /**
     * The last collaborator-change check and the gate that decided it (T-318), or null before the
     * first. Shown in Settings → Diagnostics beside the background sync, so a phone that stays
     * silent can say why.
     */
    val lastChangeCheck: Flow<ChangeCheck?> = dataStore.data.map { prefs ->
        val at = prefs[CHECK_AT_KEY] ?: return@map null
        val outcome = prefs[CHECK_OUTCOME_KEY]
            ?.let { name -> ChangeCheckOutcome.entries.firstOrNull { it.name == name } }
            ?: return@map null
        ChangeCheck(at, prefs[CHECK_ITEMS_KEY] ?: 0, outcome)
    }

    suspend fun recordChangeCheck(check: ChangeCheck) {
        dataStore.edit {
            it[CHECK_AT_KEY] = check.atMillis
            it[CHECK_ITEMS_KEY] = check.foreignItems
            it[CHECK_OUTCOME_KEY] = check.outcome.name
        }
    }

    /** Invite notifications on/off (T-319), its own switch beside the collaborator one; on by default. */
    val inviteNotificationsEnabled: Flow<Boolean> = dataStore.data.map { it[INVITES_ENABLED_KEY] ?: true }

    suspend fun setInviteNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[INVITES_ENABLED_KEY] = enabled }
    }

    /**
     * Records the invites the servers listed ([expiresAtById], epoch milliseconds) as seen on this
     * phone and returns the ids among them that had not been seen before (T-319). In the same
     * edit, forgets every seen id whose invite expired before [now], so the set holds only
     * invites that could still be listed and cannot grow without bound. An id is not forgotten
     * merely for being absent from one answer: that account's request may have failed.
     */
    suspend fun markInvitesSeen(expiresAtById: Map<String, Long>, now: Long): Set<String> {
        var unseen: Set<String> = emptySet()
        dataStore.edit { prefs ->
            val seen = decodeSeen(prefs[SEEN_INVITES_KEY].orEmpty()).filterValues { it >= now }
            unseen = expiresAtById.keys - seen.keys
            prefs[SEEN_INVITES_KEY] = encodeSeen(seen + expiresAtById)
        }
        return unseen
    }

    /** The seen invite ids with their expiry (T-319), for tests and diagnostics. */
    val seenInvites: Flow<Map<String, Long>> = dataStore.data.map { decodeSeen(it[SEEN_INVITES_KEY].orEmpty()) }

    /** The last invite check and what decided it (T-319), or null before the first. */
    val lastInviteCheck: Flow<InviteCheck?> = dataStore.data.map { prefs ->
        val at = prefs[INVITE_CHECK_AT_KEY] ?: return@map null
        val outcome = prefs[INVITE_CHECK_OUTCOME_KEY]
            ?.let { name -> InviteCheckOutcome.entries.firstOrNull { it.name == name } }
            ?: return@map null
        InviteCheck(at, prefs[INVITE_CHECK_NEW_KEY] ?: 0, outcome)
    }

    suspend fun recordInviteCheck(check: InviteCheck) {
        dataStore.edit {
            it[INVITE_CHECK_AT_KEY] = check.atMillis
            it[INVITE_CHECK_NEW_KEY] = check.newInvites
            it[INVITE_CHECK_OUTCOME_KEY] = check.outcome.name
        }
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
        val CHECK_AT_KEY = longPreferencesKey("last_change_check_at")
        val CHECK_ITEMS_KEY = intPreferencesKey("last_change_check_foreign_items")
        val CHECK_OUTCOME_KEY = stringPreferencesKey("last_change_check_outcome")
        val INVITES_ENABLED_KEY = booleanPreferencesKey("invite_notifications_enabled")

        /** Each entry is "<expires_at>:<invite id>"; a DataStore set holds strings only. */
        val SEEN_INVITES_KEY = stringSetPreferencesKey("seen_invites")
        val INVITE_CHECK_AT_KEY = longPreferencesKey("last_invite_check_at")
        val INVITE_CHECK_NEW_KEY = intPreferencesKey("last_invite_check_new")
        val INVITE_CHECK_OUTCOME_KEY = stringPreferencesKey("last_invite_check_outcome")

        fun decodeSeen(entries: Set<String>): Map<String, Long> = entries.mapNotNull { entry ->
            val expiresAt = entry.substringBefore(':', "").toLongOrNull() ?: return@mapNotNull null
            entry.substringAfter(':') to expiresAt
        }.toMap()

        fun encodeSeen(seen: Map<String, Long>): Set<String> = seen.mapTo(mutableSetOf()) { (id, expiresAt) -> "$expiresAt:$id" }
    }
}
