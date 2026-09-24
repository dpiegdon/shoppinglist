package org.p23q.shoppinglist.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.NameOrder
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.db.inTransaction
import java.util.UUID
import javax.inject.Inject

/**
 * Takes the database rather than a bare DAO because every edit here is a read-modify-write that
 * has to run inside a transaction — see [org.p23q.shoppinglist.data.repo.ItemsRepo] (T-261).
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
        listDao.activeLists().map { lists -> lists.sortedWith(NameOrder.by({ it.name.value }, { it.id })) }

    suspend fun getById(listId: String): ListEntity? = listDao.getById(listId)

    /** Live single-list observation (T-34) — reflects rename / category-order changes as they land. */
    fun observeById(listId: String): Flow<ListEntity?> = listDao.observeById(listId)

    suspend fun dirtyRows(): List<ListEntity> = listDao.dirtyRows()

    suspend fun clearDirty(ids: List<String>) = listDao.clearDirty(ids)

    /**
     * @param currency free-text label, required for an expenses list and meaningless elsewhere
     *   (T-151). The kind is fixed for the list's whole life, so both are decided here or never.
     */
    suspend fun createList(
        name: String,
        kind: String = ListKind.DEFAULT,
        currency: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val by = deviceId.get()
        val now = System.currentTimeMillis()
        listDao.upsert(
            ListEntity(
                id = id,
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
     */
    suspend fun setKind(listId: String, kind: String) =
        updateField(listId) { it.copy(kind = ListKind.of(kind).toLww(deviceId.get())) }

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
     * over [source]'s name (suffixed), category order, and notes by VALUE only — no membership, no
     * shared history. Returns the new list's id, or null if [listId] doesn't exist. Items are copied
     * separately via [org.p23q.shoppinglist.data.repo.ItemsRepo.duplicateForList].
     */
    suspend fun duplicate(listId: String): String? {
        val id = db.inTransaction {
            val source = listDao.getById(listId) ?: return@inTransaction null
            val id = UUID.randomUUID().toString()
            val by = deviceId.get()
            val now = System.currentTimeMillis()
            listDao.upsert(
                ListEntity(
                    id = id,
                    createdAt = now,
                    name = "${source.name.value} (Copy)".toLww(by, now),
                    categoryOrder = source.categoryOrder.value.toLww(by, now),
                    notes = source.notes.value.toLwwOptional(by, now),
                    kind = source.kind.value.toLww(by, now),
                    deleted = false.toLww(by, now),
                    dirty = true,
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

    /** True after the server quarantined at least one list row (T-198) — counted with the items'. */
    suspend fun blockedRowCount(): Int = listDao.blockedRowCount()

    /** A quarantined list, so a "needs attention" surface can open it even with no item blocked (T-198). */
    suspend fun firstBlockedListId(): String? = listDao.firstBlockedListId()

    /**
     * Read-modify-write in one transaction, as for an item (T-261): without it a sync merge or a
     * second edit landing between the read and the write silently wins the whole row.
     */
    private suspend fun updateField(listId: String, mutate: suspend (ListEntity) -> ListEntity) {
        val changed = db.inTransaction {
            val current = listDao.getById(listId) ?: return@inTransaction false
            // Any user edit clears a prior quarantine so the corrected row is retried on the next sync,
            // exactly as for an item (T-198).
            listDao.upsert(mutate(current).copy(dirty = true, syncBlocked = false))
            true
        }
        if (changed) syncTrigger.scheduleAfterEdit()
    }
}
