package org.p23q.shoppinglist.data

import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.LoginRequest
import org.p23q.shoppinglist.data.api.RegisterRequest
import org.p23q.shoppinglist.data.db.AppDb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates login/register/logout across the API, session state, and local mirror. An interface
 * (not just [AuthRepositoryImpl] directly) so [org.p23q.shoppinglist.ui.login.LoginViewModel] and
 * future ViewModels (A6's logout menu entry) can be tested with a fake, no network/DB required.
 */
interface AuthRepository {
    suspend fun register(email: String, password: String)
    suspend fun login(email: String, password: String)

    /**
     * Best-effort server-side token revoke, then always clears the local session regardless.
     * The mirror stays put for [login] to judge, exactly as on a forced logout — see
     * [clearLocalSession]; logging out is not a reason to throw away edits that never went out.
     */
    suspend fun logout()

    /**
     * Clears the local session WITHOUT contacting the server. For a forced logout after the server
     * has already rejected our token (401): the token is dead, so a server call is pointless.
     *
     * Leaves the local mirror alone (T-260). It used to wipe it, so that a subsequent login as a
     * different account could not see the previous account's lists — but this runs on ANY 401
     * carrying a bearer token, which per the Wire Contract includes an idle-expired session and a
     * password change on another device (that one revokes every other session by design). Edit the
     * list offline, change the password on the web, foreground the phone, and the whole unpushed
     * queue was gone. And the wipe could not have been right anyway: it happened before anyone
     * knew which account would log back in. [login] wipes instead, once it does know.
     */
    suspend fun clearLocalSession()

    fun lastOpenedListId(): String?
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthRepositoryModule {
    @Binds
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiProvider: ApiProvider,
    private val sessionState: SessionState,
    private val appDb: AppDb,
    private val defaultCurrencyState: DefaultCurrencyState,
) : AuthRepository {

    override suspend fun register(email: String, password: String) {
        apiProvider.get().register(RegisterRequest(email, password))
    }

    override suspend fun login(email: String, password: String) {
        val api = apiProvider.get()
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val response = api.login(LoginRequest(email, password, deviceLabel, PLATFORM))
        // Now — and only now — we know whose data the mirror may be shown to (T-260). A mirror left
        // behind by a different account is wiped before anything of this session is stored; the
        // returning account's own mirror is kept, unpushed edits and all, and reconciled by the
        // cursor-0 pull that follows (sessionState.clear() reset the cursor). "Not known" counts as
        // a different account: a session old enough to have no account_id (pre-T-65) can't prove
        // the mirror is this user's, and privacy wins that tie.
        if (response.accountId != sessionState.mirrorAccountId) {
            withContext(Dispatchers.IO) { appDb.clearAllTables() }
        }
        sessionState.mirrorAccountId = response.accountId
        sessionState.token = response.token
        sessionState.accountEmail = response.email
        sessionState.accountId = response.accountId
        sessionState.isAdmin = response.isAdmin
        val currency = api.getSettings().defaultCurrency
        sessionState.defaultCurrency = currency
        defaultCurrencyState.set(currency)
    }

    override suspend fun logout() {
        runCatching { apiProvider.get().logout() }
        clearLocalSession()
    }

    override suspend fun clearLocalSession() {
        // Who the surviving mirror belongs to has to outlive the session it came from, so carry it
        // across the clear (T-260). Taken from the live session when there is one, so an install
        // that logs out for the first time after this change keeps its data rather than losing it
        // to a mirror owner nobody ever recorded.
        val mirrorOwner = sessionState.accountId ?: sessionState.mirrorAccountId
        sessionState.clear()
        sessionState.mirrorAccountId = mirrorOwner
    }

    override fun lastOpenedListId(): String? = sessionState.lastOpenedListId

    private companion object {
        // Selects the server's long (62-day) inactivity window for this session —
        // appropriate here because the token lives in EncryptedSharedPreferences on a
        // personal device, unlike the web client's shorter window (T-104).
        const val PLATFORM = "android"
    }
}
