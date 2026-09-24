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
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecretStoreModule {
    @Binds
    abstract fun bindSecretStore(store: KeystoreSecretStore): SecretStore

    @Binds
    abstract fun bindLastOpenedListStore(store: KeystoreSecretStore): LastOpenedListStore

    @Binds
    abstract fun bindLegacySessionSource(source: StoredLegacySession): LegacySessionSource
}

/**
 * The bearer tokens, one per local account id, in the Keystore-backed EncryptedSharedPreferences
 * (Notes); and the device's last-opened list, which has always lived in the same file.
 *
 * The file also still holds, on an install upgraded from the single-session app, that app's keys;
 * [StoredLegacySession] reads them once for the database migration and then deletes them.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(@ApplicationContext context: Context) : SecretStore, LastOpenedListStore {
    // Built with one-shot recovery: if the encrypted keyset can't be decrypted with this device's
    // Keystore master key (a restored/transferred store on a new device), drop the file and rebuild
    // fresh so the app starts logged-out instead of crash-looping (T-37). Backup is also off (see the
    // manifest), so this only fires on device-to-device transfer or a Keystore key invalidation.
    internal val prefs: SharedPreferences = openWithRecovery(
        build = { createEncryptedPrefs(context) },
        onCorrupt = { context.deleteSharedPreferences(PREFS_FILE) },
    )

    override fun token(accountId: String): String? = prefs.getString(tokenKey(accountId), null)

    override fun setToken(accountId: String, token: String?) {
        val editor = prefs.edit()
        if (token == null) editor.remove(tokenKey(accountId)) else editor.putString(tokenKey(accountId), token)
        editor.apply()
    }

    override var lastOpenedListId: String?
        get() = prefs.getString(KEY_LAST_OPENED_LIST_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_LIST_ID, value).apply()

    private fun createEncryptedPrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    internal companion object {
        const val PREFS_FILE = "session"
        const val KEY_LAST_OPENED_LIST_ID = "last_opened_list_id"

        fun tokenKey(accountId: String) = "token:$accountId"
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
