package org.p23q.shoppinglist.data

import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the single-session app kept outside the database, as the schema-9 migration needs it: the
 * session in the encrypted preferences and the server in ServerConfig. Every field is as stored,
 * null or empty where nothing was.
 */
data class LegacySession(
    val token: String? = null,
    val accountId: String? = null,
    /** Whose lists the mirror held once the session was gone (T-260). */
    val mirrorAccountId: String? = null,
    val email: String? = null,
    val isAdmin: Boolean = false,
    val defaultCurrency: String? = null,
    val syncCursor: Long = 0,
    val ignoredInviteIds: Set<String> = emptySet(),
    val serverUrl: String? = null,
    val allowSelfSignedCerts: Boolean = false,
)

/** The single-session stores, read once by [Migration8To9][org.p23q.shoppinglist.data.db.Migration8To9]. */
interface LegacySessionSource {
    fun read(): LegacySession

    /** Stores the legacy token under [localAccountId]. The old key stays until [discard]. */
    fun adoptToken(localAccountId: String)

    /**
     * Deletes the single-session keys in the encrypted preferences. Only once the database has
     * been opened at schema 9, which is when the migration that reads them has committed.
     * ServerConfig's address is not one of them: it is the device's, and stays.
     */
    suspend fun discard()
}

@Singleton
class StoredLegacySession @Inject constructor(
    private val secrets: KeystoreSecretStore,
    private val serverConfig: ServerConfig,
) : LegacySessionSource {
    override fun read(): LegacySession {
        val prefs = secrets.prefs
        return LegacySession(
            token = prefs.getString(KEY_TOKEN, null),
            accountId = prefs.getString(KEY_ACCOUNT_ID, null),
            mirrorAccountId = prefs.getString(KEY_MIRROR_ACCOUNT_ID, null),
            email = prefs.getString(KEY_ACCOUNT_EMAIL, null),
            isAdmin = prefs.getBoolean(KEY_IS_ADMIN, false),
            defaultCurrency = prefs.getString(KEY_DEFAULT_CURRENCY, null),
            syncCursor = prefs.getLong(KEY_SYNC_CURSOR, 0L),
            ignoredInviteIds = prefs.getStringSet(KEY_IGNORED_INVITE_IDS, emptySet()).orEmpty().toSet(),
            // Room runs migrations off the main thread, on the connection it is opening, so a
            // blocking read of the DataStore is safe here.
            serverUrl = runBlocking { serverConfig.lastServerUrl() },
            allowSelfSignedCerts = runBlocking { serverConfig.lastAllowSelfSignedCerts() },
        )
    }

    override fun adoptToken(localAccountId: String) {
        secrets.prefs.getString(KEY_TOKEN, null)?.let { secrets.setToken(localAccountId, it) }
    }

    override suspend fun discard() {
        val editor = secrets.prefs.edit()
        LEGACY_KEYS.forEach { editor.remove(it) }
        editor.apply()
        // ServerConfig's address stays: it is the device's last-typed one, not the session's.
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_MIRROR_ACCOUNT_ID = "mirror_account_id"
        const val KEY_IS_ADMIN = "is_admin"
        const val KEY_DEFAULT_CURRENCY = "default_currency"
        const val KEY_SYNC_CURSOR = "sync_cursor"
        const val KEY_IGNORED_INVITE_IDS = "ignored_invite_ids"
        val LEGACY_KEYS = listOf(
            KEY_TOKEN, KEY_ACCOUNT_EMAIL, KEY_ACCOUNT_ID, KEY_MIRROR_ACCOUNT_ID, KEY_IS_ADMIN,
            KEY_DEFAULT_CURRENCY, KEY_SYNC_CURSOR, KEY_IGNORED_INVITE_IDS,
        )
    }
}
