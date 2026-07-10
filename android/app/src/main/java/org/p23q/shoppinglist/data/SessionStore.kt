package org.p23q.shoppinglist.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.data.api.TokenProvider
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
    @Binds
    abstract fun bindTokenProvider(sessionStore: SessionStore): TokenProvider

    @Binds
    abstract fun bindSessionState(sessionStore: SessionStore): SessionState
}

/**
 * Read/write session fields, kept separate from [TokenProvider] (interface segregation: OkHttp's
 * AuthInterceptor only ever needs read-only token access). AuthRepository depends on this instead
 * of the concrete [SessionStore] so it's fakeable in tests without a real Keystore.
 */
interface SessionState {
    var token: String?
    var accountEmail: String?
    var defaultCurrency: String?
    var lastOpenedListId: String?
    var syncCursor: Long

    /** Wipes all session state, e.g. on logout. */
    fun clear()
}

/** Per-device session state. The bearer token lives in the Keystore-backed EncryptedSharedPreferences (Notes). */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext context: Context) : TokenProvider, SessionState {
    // Built with one-shot recovery: if the encrypted keyset can't be decrypted with this device's
    // Keystore master key (a restored/transferred store on a new device), drop the file and rebuild
    // fresh so the app starts logged-out instead of crash-looping (T-37). Backup is also off (see the
    // manifest), so this only fires on device-to-device transfer or a Keystore key invalidation.
    private val prefs: SharedPreferences = openWithRecovery(
        build = { createEncryptedPrefs(context) },
        onCorrupt = { context.deleteSharedPreferences(PREFS_FILE) },
    )

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    override fun currentToken(): String? = token

    override var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()

    override var defaultCurrency: String?
        get() = prefs.getString(KEY_DEFAULT_CURRENCY, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_CURRENCY, value).apply()

    override var lastOpenedListId: String?
        get() = prefs.getString(KEY_LAST_OPENED_LIST_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_LIST_ID, value).apply()

    override var syncCursor: Long
        get() = prefs.getLong(KEY_SYNC_CURSOR, 0L)
        set(value) = prefs.edit().putLong(KEY_SYNC_CURSOR, value).apply()

    override fun clear() = prefs.edit().clear().apply()

    private fun createEncryptedPrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private companion object {
        const val PREFS_FILE = "session"
        const val KEY_TOKEN = "token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_DEFAULT_CURRENCY = "default_currency"
        const val KEY_LAST_OPENED_LIST_ID = "last_opened_list_id"
        const val KEY_SYNC_CURSOR = "sync_cursor"
    }
}

/**
 * Builds an encrypted store, recovering once from an unreadable keyset. [build] constructs the store;
 * if it throws because the keyset can't be decrypted with this device's master key
 * ([GeneralSecurityException] — e.g. AEADBadTagException after a backup/transfer lands on a new
 * device) or the file is unreadable ([IOException]), [onCorrupt] discards the bad file and we build
 * once more (fresh keysets under the current master key). A second failure propagates — genuinely
 * unrecoverable. Starting empty just means re-login, and all list state re-syncs (T-37).
 *
 * Extracted (and `internal`) so the recovery control-flow is unit-testable without a real Keystore,
 * which [EncryptedSharedPreferences] requires and Robolectric can't provide.
 */
internal fun <T> openWithRecovery(build: () -> T, onCorrupt: () -> Unit): T =
    try {
        build()
    } catch (_: GeneralSecurityException) {
        onCorrupt()
        build()
    } catch (_: IOException) {
        onCorrupt()
        build()
    }
