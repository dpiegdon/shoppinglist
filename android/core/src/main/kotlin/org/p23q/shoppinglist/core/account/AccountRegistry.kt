package org.p23q.shoppinglist.core.account

import kotlinx.coroutines.CancellationException
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
                rows.filter { it.outdated }.forEach { dao.update(it.copy(outdated = false)) }
                val fresh = rows.map { it.copy(outdated = false) }
                state.value = fresh
                fresh
            }
        }
    }

    /** The accounts as last loaded or changed; empty before [load]. */
    fun snapshot(): List<AccountEntity> = state.value.orEmpty()

    fun get(id: String): AccountEntity? = snapshot().firstOrNull { it.id == id }

    /** The server account for this URL (in any spelling) and server-side account id, if this device has it. */
    fun find(serverUrl: String, accountId: String): AccountEntity? {
        val url = normalizeServerUrl(serverUrl)
        return snapshot().firstOrNull { it.serverUrl == url && it.accountId == accountId }
    }

    /**
     * Adds [account], placed after every existing one (or replaces the one with its id).
     *
     * Throws what the table throws when it refuses the row — another account with the same server
     * and server-side id — and the copy is then as the table is.
     */
    suspend fun add(account: AccountEntity): AccountEntity {
        load()
        var added = account
        state.update { current ->
            val others = current.orEmpty().filterNot { it.id == account.id }
            added = account.copy(sortOrder = (others.maxOfOrNull { it.sortOrder } ?: -1) + 1)
            others + added
        }
        persistOrRevert(added.id)
        return added
    }

    /**
     * Applies [mutate] to the account and writes it; returns the new row, or null if it is gone.
     * A row the table refuses is refused as [add] refuses it.
     */
    suspend fun update(id: String, mutate: (AccountEntity) -> AccountEntity): AccountEntity? {
        load()
        val updated = apply(id, mutate) ?: return null
        persistOrRevert(id)
        return updated
    }

    /**
     * [update] for callers that cannot suspend (an OkHttp interceptor, a view model's setter). The
     * change is visible to every reader when this returns; only the write to the table is left to
     * run in the background. [flush] waits for it. There is nobody to tell of a refused row here,
     * so the copy just goes back to what the table holds.
     */
    fun updateInBackground(id: String, mutate: (AccountEntity) -> AccountEntity) {
        apply(id, mutate) ?: return
        scope.launch {
            try {
                persistOrRevert(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Reverted; see above.
            }
        }
    }

    /**
     * Puts the accounts in the order of [ids], the user's order everywhere accounts are listed:
     * each gets its position as [AccountEntity.sortOrder]. Accounts [ids] leaves out keep their
     * relative order after the named ones; unknown ids are ignored.
     */
    suspend fun reorder(ids: List<String>) {
        load()
        state.update { current ->
            val list = current ?: return@update current
            val byId = list.associateBy { it.id }
            val named = ids.distinct().mapNotNull { byId[it] }
            (named + list.filterNot { it in named }).mapIndexed { index, account -> account.copy(sortOrder = index) }
        }
        snapshot().forEach { persistOrRevert(it.id) }
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

    /** Writes the copy's row; if the table refuses it, puts the table's row back in the copy. */
    private suspend fun persistOrRevert(id: String) = writeMutex.withLock {
        // The copy's row as it is NOW, not as it was when this write was queued.
        val row = get(id) ?: return@withLock
        try {
            if (dao.update(row) == 0) dao.insert(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stored = dao.get(id)
            state.update { current ->
                val list = current.orEmpty()
                if (stored == null) list.filterNot { it.id == id } else list.map { if (it.id == id) stored else it }
            }
            throw e
        }
    }

    companion object {
        fun encodeIds(ids: Set<String>): String = Json.encodeToString(ids.sorted())

        fun decodeIds(json: String): Set<String> =
            runCatching { Json.decodeFromString<List<String>>(json).toSet() }.getOrDefault(emptySet())
    }
}

/**
 * A server URL as accounts store it, one spelling per server: scheme and host in lower case, no
 * default port (443 for https, 80 for http), the path as typed, and always ending in '/' so that
 * API paths resolve underneath it. Two URLs name the same server exactly when this makes them
 * equal, which is what "the same account" (T-260) and the schema-9 migration go by.
 */
fun normalizeServerUrl(url: String): String {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return withTrailingSlash(trimmed)
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val rest = trimmed.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    val userInfo = authority.substring(0, authority.lastIndexOf('@') + 1)
    val hostPort = authority.substring(userInfo.length)
    // A ':' inside an IPv6 literal's brackets is not the port separator.
    val portSeparator = hostPort.lastIndexOf(':').takeIf { it > hostPort.lastIndexOf(']') } ?: -1
    val host = (if (portSeparator >= 0) hostPort.substring(0, portSeparator) else hostPort).lowercase()
    val port = if (portSeparator >= 0) hostPort.substring(portSeparator + 1) else ""
    val isDefaultPort = port.isEmpty() || (scheme == "https" && port == "443") || (scheme == "http" && port == "80")
    val canonicalPort = if (isDefaultPort) "" else ":$port"
    return withTrailingSlash("$scheme://$userInfo$host$canonicalPort${rest.substring(authorityEnd)}")
}

private fun withTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

/** The default label of an account on [serverUrl]: the server's host, or the URL if it has none. */
fun serverLabel(serverUrl: String): String =
    runCatching { java.net.URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: serverUrl
