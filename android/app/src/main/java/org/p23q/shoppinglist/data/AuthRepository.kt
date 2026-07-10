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

    /** Best-effort server-side token revoke, then always clears local session + mirror regardless. */
    suspend fun logout()

    /**
     * Clears local session + mirror WITHOUT contacting the server. For a forced logout after the
     * server has already rejected our token (401): the token is dead, so a server call is pointless.
     * Wipes the mirror (same as [logout]) so a subsequent login as a different account can't see the
     * previous account's local lists.
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
) : AuthRepository {

    override suspend fun register(email: String, password: String) {
        apiProvider.get().register(RegisterRequest(email, password))
    }

    override suspend fun login(email: String, password: String) {
        val api = apiProvider.get()
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val response = api.login(LoginRequest(email, password, deviceLabel))
        sessionState.token = response.token
        sessionState.accountEmail = response.email
        sessionState.defaultCurrency = api.getSettings().defaultCurrency
    }

    override suspend fun logout() {
        runCatching { apiProvider.get().logout() }
        clearLocalSession()
    }

    override suspend fun clearLocalSession() {
        sessionState.clear()
        // clearAllTables() is a blocking call; Room refuses to run it on the calling thread if
        // that happens to be the main thread (viewModelScope.launch defaults to Dispatchers.Main).
        withContext(Dispatchers.IO) { appDb.clearAllTables() }
    }

    override fun lastOpenedListId(): String? = sessionState.lastOpenedListId
}
