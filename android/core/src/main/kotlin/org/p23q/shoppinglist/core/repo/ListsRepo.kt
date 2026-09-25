package org.p23q.shoppinglist.core.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.NameOrder
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ListEntity
import org.p23q.shoppinglist.core.db.inTransaction
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.p23q.shoppinglist.core.sync.SyncTrigger
import java.util.UUID
import javax.inject.Inject

/**
 * Every list id here is a local one ([ListEntity.localId]); only the sync engine deals in server ids.
 *
 * Takes the database rather than a bare DAO because every edit here is a read-modify-write that
 * has to run inside a transaction — see [org.p23q.shoppinglist.core.repo.ItemsRepo] (T-261).
 */
class ListsRepo @Inject constructor(
    private val db: AppDb,
    private val deviceId: DeviceIdProvider,
    private val syncTrigger: SyncTrigger,
) {
    private val listDao = db.listDao()

    /** Every live list in the shared name order (T-176) — sorted here, not in SQL, because SQLite's
     *  NOCASE folds only A-Z and the web orders by the same rules as this. */
    fun activeLists(): Flow<List<ListEntity>> =
        listDao.activeLists().map { lists -> lists.sortedWith(NameOrder.by({ it.name.value }, { it.serverId })) }

    suspend fun getById(listId: String): ListEntity? = listDao.get(listId)

    /**
     * The server id of list [listId], for an API call that names the list; null once this phone no
     * longer holds it.
     */
    suspend fun serverIdOf(listId: String): String? = listDao.get(listId)?.serverId

    /**
     * The local id of [accountId]'s row for the list its server calls [serverId], if this phone
     * holds it: for a server answer that names a list, such as an invite's redemption.
     */
    suspend fun localIdForServerId(accountId: String, serverId: String): String? =
        listDao.getByServerId(accountId, serverId)?.localId

    /** Live single-list observation (T-34) — reflects rename / category-order changes as they land. */
    fun observeById(listId: String): Flow<ListEntity?> = listDao.observe(listId)

    /**
     * @param accountId the local id of the account the list is created in; it never moves.
     * @param currency free-text label, required for an expenses list and meaningless elsewhere
     *   (T-151). The kind is fixed for the list's whole life, so both are decided here or never.
     * @return the new list's local id. Its server id is minted here too.
     * @throws IllegalArgumentException for a kind the account cannot hold ([ListKind.choices]):
     *   the local area has no ledgers (T-293).
     */
    suspend fun create(
        accountId: String,
        name: String,
        kind: String = ListKind.DEFAULT,
        currency: String? = null,
    ): String {
        require(ListKind.of(kind) in ListKind.choices(isServerAccount(accountId))) { "No $kind list in account $accountId" }
        val id = UUID.randomUUID().toString()
        val by = deviceId.get()
        val now = System.currentTimeMillis()
        listDao.upsert(
            ListEntity(
                localId = id,
                serverId = UUID.randomUUID().toString(),
                accountId = accountId,
                createdAt = now,
                name = name.toLww(by, now),
                categoryOrder = encodeCategoryOrder(emptyList()).toLww(by, now),
                notes = null.toLwwOptional(by, now),
                kind = ListKind.of(kind).toLww(by, now),
                currency = currency?.trim()?.takeIf { it.isNotEmpty() }.toLwwOptional(by, now),
                deleted = false.toLww(by, now),
                dirty = true,
            ),
        )
        syncTrigger.scheduleAfterEdit()
        return id
    }

    /**
     * Convert between shopping list and checklist (T-110). Non-destructive — the item schema is
     * identical for both, so hidden fields (stores/price/quantity) survive and reappear on switching
     * back. An ordinary LWW field write, so a stale device can't silently revert it.
     *
     * Refuses, returning false, a kind the list's account cannot hold: a list in the local area
     * never becomes a ledger (T-293).
     */
    suspend fun setKind(listId: String, kind: String): Boolean {
        val next = ListKind.of(kind)
        val list = listDao.get(listId) ?: return false
        if (next !in ListKind.choices(isServerAccount(list.accountId))) return false
        updateField(listId) { it.copy(kind = next.toLww(deviceId.get())) }
        return true
    }

    /** Whether [accountId] is a server account; the local area, or an account gone, is not. */
    private suspend fun isServerAccount(accountId: String): Boolean = db.accountDao().get(accountId)?.isServer == true

    suspend fun rename(listId: String, name: String) =
        updateField(listId) { it.copy(name = name.toLww(deviceId.get())) }

    suspend fun setCategoryOrder(listId: String, order: List<String>) =
        updateField(listId) { it.copy(categoryOrder = encodeCategoryOrder(order).toLww(deviceId.get())) }

    /** Free-text, not-regularly-needed info (T-62) — list-properties-dialog only, synced like any other field. */
    suspend fun setNotes(listId: String, notes: String?) =
        updateField(listId) { it.copy(notes = notes.toLwwOptional(deviceId.get())) }

    /** Tombstone: [ListEntity.deleted] flips true, the row itself is retained for sync. */
    suspend fun delete(listId: String) = updateField(listId) { it.copy(deleted = true.toLww(deviceId.get())) }

    /**
     * Solo-owned snapshot copy (T-63): a new list with its own id and fresh field-clocks, carrying
     * over [source]'s name (suffixed), category order, notes, kind and currency by VALUE only — no
     * membership, no close votes, no shared history. Returns the new list's id, or null if [listId]
     * or [targetAccountId] doesn't exist. Items are copied separately via
     * [org.p23q.shoppinglist.core.repo.ItemsRepo.duplicateForList].
     *
     * @param copySuffix what follows the source's name, after a space: "(Copy)" in the language of
     *   whoever makes the copy. The name is synced data, so it stays in that language (T-302);
     *   this module cannot read the app's resources, so the caller passes it.
     * @param targetAccountId the account the copy goes to (T-294): the source's own by default,
     *   or another account on this phone, the local area included. A copy into a server account
     *   is dirty and goes out on that account's next sync; one into the local area never does.
     * @throws IllegalArgumentException for a ledger into another account: its debts are between
     *   the source account's members, and the local area holds no ledgers at all.
     */
    suspend fun duplicate(listId: String, copySuffix: String, targetAccountId: String? = null): String? {
        val id = db.inTransaction {
            val source = listDao.get(listId) ?: return@inTransaction null
            val accountId = targetAccountId ?: source.accountId
            val target = db.accountDao().get(accountId) ?: return@inTransaction null
            val kind = ListKind.of(source.kind.value)
            require(!ListKind.isExpenses(kind) || accountId == source.accountId) {
                "A ledger is not copied into another account"
            }
            require(kind in ListKind.choices(target.isServer)) { "No $kind list in account $accountId" }
            val id = UUID.randomUUID().toString()
            val by = deviceId.get()
            val now = System.currentTimeMillis()
            listDao.upsert(
                ListEntity(
                    localId = id,
                    serverId = UUID.randomUUID().toString(),
                    accountId = accountId,
                    createdAt = now,
                    name = "${source.name.value} $copySuffix".toLww(by, now),
                    categoryOrder = source.categoryOrder.value.toLww(by, now),
                    notes = source.notes.value.toLwwOptional(by, now),
                    kind = kind.toLww(by, now),
                    currency = source.currency.value.toLwwOptional(by, now),
                    deleted = false.toLww(by, now),
                    dirty = true,
                    // The roster and close votes are the server's, and the copy has neither yet.
                ),
            )
            id
        }
        if (id != null) syncTrigger.scheduleAfterEdit()
        return id
    }

    /** Real delete, not the LWW tombstone — only for leaving a shared list (A9), never synced. */
    suspend fun removeLocally(listId: String) = listDao.hardDelete(listId)

    /**
     * The list's roster as the server last reported it (T-152). Decoded from the mirrored column
     * rather than fetched, so the expense form's defaults and the balances screen work offline. A
     * row written before this column existed, or by a server too old to send it, decodes to empty.
     */
    fun decodeMembers(json: String): List<ListMember> =
        runCatching { Json.decodeFromString<List<ListMember>>(json) }.getOrDefault(emptyList())

    /** Who has agreed to close this list (T-157), as the server last reported. */
    fun decodeCloseVotes(json: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())

    fun decodeCategoryOrder(json: String): List<String> = Json.decodeFromString(json)

    private fun encodeCategoryOrder(order: List<String>): String = Json.encodeToString(order)

    /** A quarantined list, so a "needs attention" surface can open it even with no item blocked (T-198). */
    suspend fun firstBlockedListId(): String? = listDao.firstBlockedListId()

    /**
     * Read-modify-write in one transaction, as for an item (T-261): without it a sync merge or a
     * second edit landing between the read and the write silently wins the whole row.
     */
    private suspend fun updateField(listId: String, mutate: suspend (ListEntity) -> ListEntity) {
        val changed = db.inTransaction {
            val current = listDao.get(listId) ?: return@inTransaction false
            // Any user edit clears a prior quarantine so the corrected row is retried on the next sync,
            // exactly as for an item (T-198).
            listDao.upsert(mutate(current).copy(dirty = true, syncBlocked = false))
            true
        }
        if (changed) syncTrigger.scheduleAfterEdit()
    }
}
