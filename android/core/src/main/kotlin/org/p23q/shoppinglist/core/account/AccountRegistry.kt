package org.p23q.shoppinglist.core.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.inTransaction

/**
 * The accounts on this device, and the only code that writes the `accounts` table.
 *
 * Being the only writer is what lets it keep the table in memory: the copy here is always what the
 * table holds or is about to hold, so a read needs no query and can be synchronous, which the
 * OkHttp interceptors and the single-account view models both need. A change applies to the copy
 * at once and is then written through; each write stores the copy's current row, so writes that
 * land out of order still leave the table matching the copy.
 *
 * [load] must have run before the synchronous reads mean anything. It also clears [AccountEntity.outdated]:
 * that flag is a statement about the build that got the 426, and a new process may be a newer
 * build. If it is not, its first request is refused again and raises the flag again.
 */
class AccountRegistry(
    private val db: AppDb,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dao = db.accountDao()
    private val state = MutableStateFlow<List<AccountEntity>?>(null)
    private val loadMutex = Mutex()
    private val writeMutex = Mutex()
    private val accountLocksGuard = Mutex()
    private val accountLocks = HashMap<String, Mutex>()

    /** Every account, in the user's order. Emits once [load] has run. */
    val accounts: Flow<List<AccountEntity>> = state.filterNotNull()

    /** Reads the table once per process (running the database's migrations, if due). Idempotent. */
    suspend fun load(): List<AccountEntity> {
        state.value?.let { return it }
        return loadMutex.withLock {
            state.value ?: run {
                val rows = dao.all()
                rows.filter { it.outdated }.forEach { dao.upsert(it.copy(outdated = false)) }
                val fresh = rows.map { it.copy(outdated = false) }
                state.value = fresh
                fresh
            }
        }
    }

    /** The accounts as last loaded or changed; empty before [load]. */
    fun snapshot(): List<AccountEntity> = state.value.orEmpty()

    fun get(id: String): AccountEntity? = snapshot().firstOrNull { it.id == id }

    /** The server account for this URL and server-side account id, if this device has it. */
    fun find(serverUrl: String, accountId: String): AccountEntity? =
        snapshot().firstOrNull { it.serverUrl == serverUrl && it.accountId == accountId }

    /** Adds [account], placed after every existing one (or replaces the one with its id). */
    suspend fun add(account: AccountEntity): AccountEntity {
        load()
        var added = account
        state.update { current ->
            val others = current.orEmpty().filterNot { it.id == account.id }
            added = account.copy(sortOrder = (others.maxOfOrNull { it.sortOrder } ?: -1) + 1)
            others + added
        }
        persist(added.id)
        return added
    }

    /** Applies [mutate] to the account and writes it; returns the new row, or null if it is gone. */
    suspend fun update(id: String, mutate: (AccountEntity) -> AccountEntity): AccountEntity? {
        load()
        val updated = apply(id, mutate) ?: return null
        persist(id)
        return updated
    }

    /**
     * [update] for callers that cannot suspend (an OkHttp interceptor, a view model's setter). The
     * change is visible to every reader when this returns; only the write to the table is left to
     * run in the background. [flush] waits for it.
     */
    fun updateInBackground(id: String, mutate: (AccountEntity) -> AccountEntity) {
        apply(id, mutate) ?: return
        scope.launch { persist(id) }
    }

    /**
     * Removes the account together with its lists and their items, in one transaction. The token
     * and the account's API client are the caller's to drop.
     */
    suspend fun remove(id: String) {
        load()
        writeMutex.withLock {
            db.inTransaction {
                db.itemDao().deleteForAccount(id)
                db.listDao().deleteForAccount(id)
                dao.delete(id)
            }
            state.update { current -> current.orEmpty().filterNot { it.id == id } }
        }
    }

    /**
     * Runs [block] holding [id]'s account lock: one sync of the account, its local sign-out or its
     * removal at a time (T-298), so a removal never lands between a sync's request and its merge.
     * Not reentrant: [block] must not take the same account's lock again.
     */
    suspend fun <T> withAccountLock(id: String, block: suspend () -> T): T {
        val lock = accountLocksGuard.withLock { accountLocks.getOrPut(id) { Mutex() } }
        return lock.withLock { block() }
    }

    /** Waits for every write [updateInBackground] has started. */
    suspend fun flush() {
        scope.coroutineContext[Job]?.children?.toList()?.joinAll()
    }

    private fun apply(id: String, mutate: (AccountEntity) -> AccountEntity): AccountEntity? {
        var result: AccountEntity? = null
        state.update { current ->
            val list = current ?: return@update current
            list.map { if (it.id == id) mutate(it).copy(id = id).also { changed -> result = changed } else it }
        }
        return result
    }

    private suspend fun persist(id: String) = writeMutex.withLock {
        // The copy's row as it is NOW, not as it was when this write was queued.
        val row = get(id) ?: return@withLock
        dao.upsert(row)
    }

    companion object {
        fun encodeIds(ids: Set<String>): String = Json.encodeToString(ids.sorted())

        fun decodeIds(json: String): Set<String> =
            runCatching { Json.decodeFromString<List<String>>(json).toSet() }.getOrDefault(emptySet())
    }
}

/** A server URL as accounts store it: always ending in '/', so API paths resolve underneath it. */
fun normalizeServerUrl(url: String): String {
    return if (url.endsWith("/")) url else "$url/"
}

/** The default label of an account on [serverUrl]: the server's host, or the URL if it has none. */
fun serverLabel(serverUrl: String): String =
    runCatching { java.net.URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: serverUrl
