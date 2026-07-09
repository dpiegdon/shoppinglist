package org.p23q.shoppinglist.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.data.api.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
    @Binds
    abstract fun bindTokenProvider(sessionStore: SessionStore): TokenProvider
}

/** Per-device session state. The bearer token lives in the Keystore-backed EncryptedSharedPreferences (Notes). */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext context: Context) : TokenProvider {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    override fun currentToken(): String? = token

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()

    var defaultCurrency: String?
        get() = prefs.getString(KEY_DEFAULT_CURRENCY, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_CURRENCY, value).apply()

    var lastOpenedListId: String?
        get() = prefs.getString(KEY_LAST_OPENED_LIST_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_LIST_ID, value).apply()

    var syncCursor: Long
        get() = prefs.getLong(KEY_SYNC_CURSOR, 0L)
        set(value) = prefs.edit().putLong(KEY_SYNC_CURSOR, value).apply()

    /** Wipes all session state, e.g. on logout. */
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_DEFAULT_CURRENCY = "default_currency"
        const val KEY_LAST_OPENED_LIST_ID = "last_opened_list_id"
        const val KEY_SYNC_CURSOR = "sync_cursor"
    }
}
