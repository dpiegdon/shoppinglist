package org.p23q.shoppinglist.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.api.AppJson
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.db.inTransaction
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.p23q.shoppinglist.core.db.unblocked
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.DeviceIdProvider
import java.util.UUID
import javax.inject.Inject

@Serializable
data class Price(val amount: String, val currency: String?)

/**
 * Every edit here is a read-modify-write — read the row, stamp one field's LWW clock, write the
 * whole row back — so it must run inside a database transaction (T-261). Without one, a sync merge
 * (which is the same shape) or a second edit landing in the window between the read and the write
 * is overwritten whole-row, and because the overwrite carries the pre-edit clocks the row is not
 * even left dirty: the edit is lost with nothing queued to recover it. The list screen syncs every
 * 5 s while it is open, so the window is hit in normal use. Hence [db] rather than a bare DAO.
 */
class ItemsRepo @Inject constructor(
    private val db: AppDb,
    private val deviceId: DeviceIdProvider,
    private val syncTrigger: SyncTrigger,
) {
    private val itemDao = db.itemDao()

    fun itemsForListByStatus(listId: String, status: Status): Flow<List<ItemEntity>> =
        itemDao.itemsForListByStatus(listId, status.wireValue)

    /** All list-visible items (todo + checked) in one stream — see [ItemDao.itemsForList]. */
    fun itemsForList(listId: String): Flow<List<ItemEntity>> = itemDao.itemsForList(listId)

    /** Open (todo) item count per list id, for the Overview cards (T-42). */
    fun openItemCounts(): Flow<Map<String, Int>> =
        itemDao.openItemCounts().map { rows -> rows.associate { it.listId to it.openCount } }

    fun searchRegistry(listId: String, nameQuery: String): Flow<List<ItemEntity>> =
        itemDao.searchRegistry(listId, nameQuery)

    fun distinctCategories(listId: String): Flow<List<String>> = itemDao.distinctCategories(listId)

    /** Category values with duplicates, for frequency-weighted canonical casing (T-108). */
    fun categoryValues(listId: String): Flow<List<String>> = itemDao.categoryValues(listId)

    /** Every expense entry across every list, live (T-265) — see [org.p23q.shoppinglist.core.db.ItemDao.expenseItems]. */
    fun expenseItems(): Flow<List<ItemEntity>> = itemDao.expenseItems()

    /** Encoded stores lists, one JSON array per item — decode with [decodeStores] (T-138). */
    fun storeValues(listId: String): Flow<List<String>> = itemDao.storeValues(listId)

    /** One-shot equivalent of [itemsForList] — see [org.p23q.shoppinglist.core.db.ItemDao.itemsForListOnce] (T-265). */
    suspend fun itemsForListOnce(listId: String): List<ItemEntity> = itemDao.itemsForListOnce(listId)

    /** All non-deleted items in a list (any status), one-shot — for a category recase (T-108). */
    suspend fun activeItemsForListOnce(listId: String): List<ItemEntity> =
        itemDao.activeItemsForListOnce(listId)

    suspend fun getById(itemId: String): ItemEntity? = itemDao.getById(itemId)

    /** Local pre-check mirroring the server's case-insensitive per-list name uniqueness rule. */
    suspend fun findByExactName(listId: String, name: String, excludingId: String = ""): ItemEntity? =
        itemDao.findByExactName(listId, name, excludingId)

    suspend fun dirtyRows(): List<ItemEntity> = itemDao.dirtyRows()

    suspend fun clearDirty(ids: List<String>) = itemDao.clearDirty(ids)

    suspend fun createItem(listId: String, name: String, status: Status = Status.TODO): String {
        val id = UUID.randomUUID().toString()
        val by = deviceId.get()
        val now = System.currentTimeMillis()
        itemDao.upsert(
            ItemEntity(
                id = id,
                listId = listId,
                createdAt = now,
                name = name.toLww(by, now),
                category = null.toLwwOptional(by, now),
                stores = encodeStores(emptyList()).toLww(by, now),
                quantity = null.toLwwOptional(by, now),
                price = null.toLwwOptional(by, now),
                note = null.toLwwOptional(by, now),
                status = status.wireValue.toLww(by, now),
                deleted = false.toLww(by, now),
                dirty = true,
            ),
        )
        syncTrigger.scheduleAfterEdit()
        return id
    }

    /**
     * Create an expense (T-153). Distinct from [createItem] because an item on an expenses list is
     * invalid without its money tuple — the server refuses one — so the two are written together
     * under a single clock rather than as a create followed by an edit.
     */
    suspend fun createExpense(listId: String, name: String, expense: Expense, note: String? = null): String {
        val id = UUID.randomUUID().toString()
        val by = deviceId.get()
        val now = System.currentTimeMillis()
        itemDao.upsert(
            ItemEntity(
                id = id,
                listId = listId,
                createdAt = now,
                name = name.toLww(by, now),
                category = null.toLwwOptional(by, now),
                stores = encodeStores(emptyList()).toLww(by, now),
                quantity = null.toLwwOptional(by, now),
                price = null.toLwwOptional(by, now),
                note = note.toLwwOptional(by, now),
                status = Status.TODO.wireValue.toLww(by, now),
                expense = encodeExpense(expense).toLwwOptional(by, now),
                deleted = false.toLww(by, now),
                dirty = true,
            ),
        )
        syncTrigger.scheduleAfterEdit()
        return id
    }

    /** Replace an expense's money tuple; the whole object is one LWW field (T-151). */
    suspend fun setExpense(itemId: String, expense: Expense) =
        updateField(itemId) { it.copy(expense = encodeExpense(expense).toLwwOptional(deviceId.get())) }

    /**
     * The stored value is raw wire JSON, decoded here rather than at the sync boundary, so it goes
     * through the app's lenient [AppJson] and not Json.Default (T-205). A strict decode returns
     * null on the first unknown key, and the screens drop a null expense — so one new key in a
     * server release would empty every expense list on every device until the app was updated,
     * silently and with no error anywhere.
     */
    fun decodeExpense(json: String?): Expense? =
        json?.let { runCatching { AppJson.decodeFromString<Expense>(it) }.getOrNull() }

    fun encodeExpense(expense: Expense): String = AppJson.encodeToString(expense)

    suspend fun rename(itemId: String, name: String) =
        updateField(itemId) { it.copy(name = name.toLww(deviceId.get())) }

    suspend fun setStatus(itemId: String, status: Status) =
        updateField(itemId) { it.copy(status = status.wireValue.toLww(deviceId.get())) }

    /**
     * Bulk 'clear checked': every checked item in [listId] -> backlog, in one dirty batch so it's a
     * single sync push (T-35). Each row is re-stamped through the same LWW path as [setStatus] (its
     * status clock advances and dirty flips), NOT a bare `UPDATE status_value` — that would change
     * the value but not the clock/dirty, so the move would never sync and would lose any concurrent
     * merge. Returns the affected ids so the caller can offer a snackbar undo ([setStatusBulk] back
     * to checked).
     */
    suspend fun clearChecked(listId: String): List<String> {
        val checked = db.inTransaction {
            val checked = itemDao.itemsForListByStatusOnce(listId, Status.CHECKED.wireValue)
            val by = deviceId.get()
            val now = System.currentTimeMillis()
            checked.forEach { item ->
                itemDao.upsert(item.copy(status = Status.BACKLOG.wireValue.toLww(by, now), dirty = true).unblocked())
            }
            checked
        }
        if (checked.isEmpty()) return emptyList()
        syncTrigger.scheduleAfterEdit()
        return checked.map { it.id }
    }

    /** Batched [setStatus] over several ids — one dirty batch, one sync push. Backs clear-checked's undo (T-35). */
    suspend fun setStatusBulk(itemIds: List<String>, status: Status) {
        if (itemIds.isEmpty()) return
        val changed = db.inTransaction {
            val by = deviceId.get()
            val now = System.currentTimeMillis()
            var changed = false
            itemIds.forEach { id ->
                val current = itemDao.getById(id) ?: return@forEach
                itemDao.upsert(current.copy(status = status.wireValue.toLww(by, now), dirty = true).unblocked())
                changed = true
            }
            changed
        }
        if (changed) syncTrigger.scheduleAfterEdit()
    }

    suspend fun setCategory(itemId: String, category: String?) =
        updateField(itemId) { it.copy(category = category.toLwwOptional(deviceId.get())) }

    /** Stamp the same category on many items at once (T-108 recase/rename); one shared clock. */
    suspend fun setCategoryBulk(itemIds: List<String>, category: String?) {
        if (itemIds.isEmpty()) return
        val changed = db.inTransaction {
            val by = deviceId.get()
            val now = System.currentTimeMillis()
            var changed = false
            itemIds.forEach { id ->
                val current = itemDao.getById(id) ?: return@forEach
                itemDao.upsert(current.copy(category = category.toLwwOptional(by, now), dirty = true).unblocked())
                changed = true
            }
            changed
        }
        if (changed) syncTrigger.scheduleAfterEdit()
    }

    suspend fun setStores(itemId: String, stores: List<String>) =
        updateField(itemId) { it.copy(stores = encodeStores(stores).toLww(deviceId.get())) }

    suspend fun setQuantity(itemId: String, quantity: String?) =
        updateField(itemId) { it.copy(quantity = quantity.toLwwOptional(deviceId.get())) }

    suspend fun setPrice(itemId: String, amount: String?, currency: String?) =
        updateField(itemId) {
            val json = amount?.let { Json.encodeToString(Price(it, currency)) }
            it.copy(price = json.toLwwOptional(deviceId.get()))
        }

    suspend fun setNote(itemId: String, note: String?) =
        updateField(itemId) { it.copy(note = note.toLwwOptional(deviceId.get())) }

    /** Tombstone: [ItemEntity.deleted] flips true, the row itself is retained for sync/undo. */
    suspend fun delete(itemId: String) = updateField(itemId) { it.copy(deleted = true.toLww(deviceId.get())) }

    /**
     * Snapshot-copies every non-deleted item of [sourceListId] into [targetListId] as a fresh row
     * (new id, new created_at, fresh field-clocks) with each field's current VALUE carried over —
     * status included, since duplicate is a template/snapshot copy, not a "reset for next week"
     * action (T-63). Returns the number of items copied.
     */
    suspend fun duplicateForList(sourceListId: String, targetListId: String): Int {
        val copied = db.inTransaction {
            val items = itemDao.activeItemsForListOnce(sourceListId)
            val by = deviceId.get()
            val now = System.currentTimeMillis()
            items.forEach { source ->
                itemDao.upsert(
                    ItemEntity(
                        id = UUID.randomUUID().toString(),
                        listId = targetListId,
                        createdAt = now,
                        name = source.name.value.toLww(by, now),
                        category = source.category.value.toLwwOptional(by, now),
                        stores = source.stores.value.toLww(by, now),
                        quantity = source.quantity.value.toLwwOptional(by, now),
                        price = source.price.value.toLwwOptional(by, now),
                        note = source.note.value.toLwwOptional(by, now),
                        status = source.status.value.toLww(by, now),
                        deleted = false.toLww(by, now),
                        dirty = true,
                    ),
                )
            }
            items.size
        }
        if (copied > 0) syncTrigger.scheduleAfterEdit()
        return copied
    }

    /** Reverses [delete] (Notes: registry delete offers a snackbar undo). */
    suspend fun restore(itemId: String) = updateField(itemId) { it.copy(deleted = false.toLww(deviceId.get())) }

    /** Real delete, not the LWW tombstone — only for leaving a shared list (A9), never synced. */
    suspend fun hardDeleteByListId(listId: String) = itemDao.hardDeleteByListId(listId)

    fun decodeStores(json: String): List<String> = Json.decodeFromString(json)

    fun decodePrice(json: String?): Price? = json?.let { Json.decodeFromString(it) }

    private fun encodeStores(stores: List<String>): String = Json.encodeToString(stores)

    /** True after the server quarantined at least one row (T-32) — for a "needs attention" hint. */
    suspend fun blockedRowCount(): Int = itemDao.blockedRowCount()

    /** One quarantined row (or null), so a "needs attention" surface can open the list holding it (T-47). */
    suspend fun firstBlockedItem(): ItemEntity? = itemDao.firstBlockedItem()

    /**
     * The one read-modify-write every single-field edit goes through, wrapped in a transaction so
     * the row cannot change between the read and the write (T-261).
     *
     * `internal`, not private, only so [org.p23q.shoppinglist.data.repo.ItemsRepoTest] can run code
     * inside that window: the transaction is invisible from outside it.
     */
    internal suspend fun updateField(itemId: String, mutate: suspend (ItemEntity) -> ItemEntity) {
        val changed = db.inTransaction {
            val current = itemDao.getById(itemId) ?: return@inTransaction false
            // Any user edit clears a prior quarantine so the corrected row is retried on the next sync.
            itemDao.upsert(mutate(current).copy(dirty = true).unblocked())
            true
        }
        if (changed) syncTrigger.scheduleAfterEdit()
    }
}
