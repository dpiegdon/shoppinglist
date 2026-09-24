package org.p23q.shoppinglist.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.repo.ListsRepo
import javax.inject.Inject

/**
 * The account a list belongs to, for the screens that show one list (T-292): its row, its API
 * client and whether the phone holds others. A list never moves between accounts, so the list row
 * is all a screen needs to know whose it is.
 */
class ListAccounts @Inject constructor(
    private val listsRepo: ListsRepo,
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
) {
    /** The account [listId] belongs to; null when the list or its account is gone. */
    suspend fun accountOf(listId: String): AccountEntity? {
        registry.load()
        return listsRepo.getById(listId)?.accountId?.let(registry::get)
    }

    /**
     * The API client of [listId]'s account. Throws [IllegalStateException] when the list has no
     * server account, as a request with no account to send it for is a programming error.
     */
    suspend fun api(listId: String): Api {
        registry.load()
        val accountId = listsRepo.getById(listId)?.accountId ?: error("No list $listId")
        return sessions.get(accountId).api
    }

    /** [api], or null when the list has no server account: for the reads a screen can do without. */
    suspend fun apiOrNull(listId: String): Api? =
        accountOf(listId)?.takeIf { it.isServer && it.serverUrl != null }?.let { sessions.get(it.id).api }

    /** [listId]'s account, live: a change to its default currency reaches an open screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAccount(listId: String): Flow<AccountEntity?> =
        listsRepo.observeById(listId)
            .map { it?.accountId }
            .distinctUntilChanged()
            .flatMapLatest { accountId ->
                if (accountId == null) flowOf(null) else accounts().map { all -> all.firstOrNull { it.id == accountId } }
            }
            .distinctUntilChanged()

    /** Whether the phone holds more than one account: the screens name a list's account only then. */
    val several: Flow<Boolean> get() = accounts().map { it.size > 1 }.distinctUntilChanged()

    /** Every account, live; loads the registry first, which the app has done before any screen. */
    private fun accounts(): Flow<List<AccountEntity>> = flow {
        registry.load()
        emitAll(registry.accounts)
    }
}
