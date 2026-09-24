package org.p23q.shoppinglist.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Per-list open-item count projection ([ItemDao.openItemCounts], T-42). */
data class ListOpenCount(val listId: String, val openCount: Int)

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    /**
     * Case-insensitive exact-name lookup for the local uniqueness pre-check (Global Constraints:
     * item names unique per list, case-insensitive). [excludingId] takes an empty-string sentinel
     * (item ids are UUIDs, never "") rather than a nullable bind, so a rename can exclude itself.
     */
    @Query(
        "SELECT * FROM items WHERE listId = :listId AND deleted_value = 0 " +
            "AND lower(name_value) = lower(:name) AND id != :excludingId LIMIT 1",
    )
    suspend fun findByExactName(listId: String, name: String, excludingId: String): ItemEntity?

    @Query("SELECT * FROM items WHERE listId = :listId AND status_value = :status AND deleted_value = 0")
    fun itemsForListByStatus(listId: String, status: String): Flow<List<ItemEntity>>

    /**
     * Every item shown on the list screen — todo + checked (i.e. not backlog), in ONE stream so the
     * list VM derives both sets from the same snapshot. Two separate per-status flows briefly
     * disagree during a status change (both hold the row), which duplicated a LazyColumn key and
     * crashed the screen (fix).
     */
    @Query("SELECT * FROM items WHERE listId = :listId AND status_value != 'backlog' AND deleted_value = 0")
    fun itemsForList(listId: String): Flow<List<ItemEntity>>

    /** One-shot (non-Flow) variant, for bulk ops like clear-checked that read the current set once (T-35). */
    @Query("SELECT * FROM items WHERE listId = :listId AND status_value = :status AND deleted_value = 0")
    suspend fun itemsForListByStatusOnce(listId: String, status: String): List<ItemEntity>

    /**
     * One-shot (non-Flow) variant of [itemsForList] — the SAME rows (not backlog, not deleted), for
     * a read taken once rather than observed, like the expense form's former-member numbering
     * (T-265). That numbering must be exactly what the ledger itself shows, in the same order, or
     * the two can name the same no-longer-a-member person differently; it had drifted onto
     * [activeItemsForListOnce] instead, which additionally counts backlog.
     */
    @Query("SELECT * FROM items WHERE listId = :listId AND status_value != 'backlog' AND deleted_value = 0")
    suspend fun itemsForListOnce(listId: String): List<ItemEntity>

    /** Every non-deleted item regardless of status, for a full-list snapshot like duplicate (T-63). */
    @Query("SELECT * FROM items WHERE listId = :listId AND deleted_value = 0")
    suspend fun activeItemsForListOnce(listId: String): List<ItemEntity>

    @Query(
        "SELECT * FROM items WHERE listId = :listId AND deleted_value = 0 " +
            "AND name_value LIKE '%' || :nameQuery || '%' COLLATE NOCASE",
    )
    fun searchRegistry(listId: String, nameQuery: String): Flow<List<ItemEntity>>

    @Query(
        "SELECT DISTINCT category_value FROM items " +
            "WHERE listId = :listId AND deleted_value = 0 AND category_value IS NOT NULL",
    )
    fun distinctCategories(listId: String): Flow<List<String>>

    /**
     * Every non-null category value (WITH duplicates), so the canonical-casing pick can weigh by
     * frequency (T-108) — unlike distinctCategories, which collapses casings and loses the counts.
     */
    @Query(
        "SELECT category_value FROM items " +
            "WHERE listId = :listId AND deleted_value = 0 AND category_value IS NOT NULL",
    )
    fun categoryValues(listId: String): Flow<List<String>>

    /**
     * Every item's encoded stores list (T-138). Each row holds a JSON array, not one store, so
     * the distinct set can't be a SELECT DISTINCT — the caller decodes and flattens these.
     */
    @Query("SELECT stores_value FROM items WHERE listId = :listId AND deleted_value = 0")
    fun storeValues(listId: String): Flow<List<String>>

    /** Rows to push: dirty AND not quarantined by a prior server 422 (T-32). */
    @Query("SELECT * FROM items WHERE dirty = 1 AND syncBlocked = 0")
    suspend fun dirtyRows(): List<ItemEntity>

    /** [dirtyRows] for one account's lists: what a sync with that account's server pushes. */
    @Query(
        "SELECT items.* FROM items INNER JOIN lists ON lists.id = items.listId " +
            "WHERE lists.accountId = :accountId AND items.dirty = 1 AND items.syncBlocked = 0",
    )
    suspend fun dirtyRowsForAccount(accountId: String): List<ItemEntity>

    @Query("UPDATE items SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    /**
     * Quarantine a row the server rejected (T-32); dirtyRows() then skips it until it's re-edited.
     * The refusal is kept with it (T-200) so the row can say why it is parked.
     */
    @Query(
        "UPDATE items SET syncBlocked = 1, syncBlockedCode = :code, syncBlockedAccountId = :accountId " +
            "WHERE id = :id",
    )
    suspend fun blockRow(id: String, code: String?, accountId: String?)

    @Query("SELECT COUNT(*) FROM items WHERE syncBlocked = 1")
    suspend fun blockedRowCount(): Int

    @Query(
        "SELECT COUNT(*) FROM items INNER JOIN lists ON lists.id = items.listId " +
            "WHERE lists.accountId = :accountId AND items.syncBlocked = 1",
    )
    suspend fun blockedRowCountForAccount(accountId: String): Int

    /** Per-list count of open (todo) items, for the Overview cards (T-42). */
    @Query(
        "SELECT listId, COUNT(*) AS openCount FROM items " +
            "WHERE status_value = 'todo' AND deleted_value = 0 GROUP BY listId",
    )
    fun openItemCounts(): Flow<List<ListOpenCount>>

    /**
     * Every non-deleted expense entry, across every list, for the Overview's ledger cards (T-265).
     * Recording or editing an entry touches only this table, not the list row, so a summary driven
     * by the lists flow alone goes stale until something else changes it — a rename, a pull, process
     * death. Live here instead, and joined to the list roster by listId in the view model.
     */
    @Query("SELECT * FROM items WHERE deleted_value = 0 AND expense_value IS NOT NULL")
    fun expenseItems(): Flow<List<ItemEntity>>

    /** A quarantined row, so the sync-health surface can send the user to the list that holds it (T-47). */
    @Query("SELECT * FROM items WHERE syncBlocked = 1 LIMIT 1")
    suspend fun firstBlockedItem(): ItemEntity?

    /** Real delete, not the LWW tombstone (A9: leaving a shared list) — never queued for sync. */
    @Query("DELETE FROM items WHERE listId = :listId")
    suspend fun hardDeleteByListId(listId: String)

    /**
     * Drops every row the server can reproduce, keeping the ones it cannot (T-259). The complement
     * of the push queue: a dirty row is an edit the server has not acknowledged, and a quarantined
     * row is one it refused and the user has not corrected yet — [dirtyRows] does not even offer
     * that one, so no amount of pushing would save it.
     *
     * This is what a `410 full_resync_required` runs instead of clearing the table: the account's
     * cursor is too old for an incremental pull, so the mirror has to be re-based on a cursor-0
     * pull, but "re-base" must not mean "delete the week of offline edits that has not gone out
     * yet". What is kept is then reconciled by the ordinary field-level LWW merge of that pull.
     */
    @Query("DELETE FROM items WHERE dirty = 0 AND syncBlocked = 0")
    suspend fun deleteSyncedRows()

    /** [deleteSyncedRows] for one account's lists only: the 410 re-base and a logout are per account. */
    @Query(
        "DELETE FROM items WHERE dirty = 0 AND syncBlocked = 0 " +
            "AND listId IN (SELECT id FROM lists WHERE accountId = :accountId)",
    )
    suspend fun deleteSyncedRowsForAccount(accountId: String)

    /** Every item of one account's lists, for removing the account from this device. */
    @Query("DELETE FROM items WHERE listId IN (SELECT id FROM lists WHERE accountId = :accountId)")
    suspend fun deleteForAccount(accountId: String)

    /**
     * Drop one row outright. Used when the server refuses a write for good (a closed expenses
     * list, T-157): the local row can never be pushed and can never be overwritten by a pull,
     * since its clocks are newer, so the only way back to the truth is to fetch it again.
     */
    @Query("DELETE FROM items WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface ListDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: ListEntity)

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun getById(id: String): ListEntity?

    /** Live single-list observation — screens use this so a rename / category-order change (local
     *  or arriving via sync) reflects without recreating the screen (T-34). */
    @Query("SELECT * FROM lists WHERE id = :id")
    fun observeById(id: String): Flow<ListEntity?>

    // Unordered on purpose: ListsRepo sorts in the shared name order (T-176).
    @Query("SELECT * FROM lists WHERE deleted_value = 0")
    fun activeLists(): Flow<List<ListEntity>>

    /** Any non-deleted list id, for the accountId self-heal's members lookup (T-74). */
    @Query("SELECT id FROM lists WHERE deleted_value = 0 LIMIT 1")
    suspend fun anyActiveListId(): String?

    /** Rows to push: dirty AND not quarantined by a prior server 422 (T-198), as for items. */
    @Query("SELECT * FROM lists WHERE dirty = 1 AND syncBlocked = 0")
    suspend fun dirtyRows(): List<ListEntity>

    @Query("SELECT * FROM lists WHERE accountId = :accountId AND dirty = 1 AND syncBlocked = 0")
    suspend fun dirtyRowsForAccount(accountId: String): List<ListEntity>

    @Query("SELECT COUNT(*) FROM lists WHERE accountId = :accountId AND syncBlocked = 1")
    suspend fun blockedRowCountForAccount(accountId: String): Int

    /** Any non-deleted list of one account, for that account's accountId self-heal (T-74). */
    @Query("SELECT id FROM lists WHERE accountId = :accountId AND deleted_value = 0 LIMIT 1")
    suspend fun anyActiveListIdForAccount(accountId: String): String?

    /** The ids of one account's lists, local copies only. */
    @Query("SELECT id FROM lists WHERE accountId = :accountId")
    suspend fun idsForAccount(accountId: String): List<String>

    /** Quarantine a list the server rejected (T-198); dirtyRows() then skips it until it's re-edited. */
    @Query("UPDATE lists SET syncBlocked = 1 WHERE id = :id")
    suspend fun blockRow(id: String)

    @Query("SELECT COUNT(*) FROM lists WHERE syncBlocked = 1")
    suspend fun blockedRowCount(): Int

    /** A quarantined list, so the sync-health surface can open it even when no item is blocked (T-198). */
    @Query("SELECT id FROM lists WHERE syncBlocked = 1 LIMIT 1")
    suspend fun firstBlockedListId(): String?

    @Query("UPDATE lists SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    /** The list twin of [ItemDao.deleteSyncedRows] (T-259) — keeps whatever is still unpushed. */
    @Query("DELETE FROM lists WHERE dirty = 0 AND syncBlocked = 0")
    suspend fun deleteSyncedRows()

    /**
     * The list twin of [ItemDao.deleteSyncedRowsForAccount]. Run after it: a list that still holds
     * an item is kept even when the list itself is clean, so no surviving unpushed item is left
     * pointing at a list that is no longer here (and so at no account to push it to).
     */
    @Query(
        "DELETE FROM lists WHERE accountId = :accountId AND dirty = 0 AND syncBlocked = 0 " +
            "AND id NOT IN (SELECT listId FROM items)",
    )
    suspend fun deleteSyncedRowsForAccount(accountId: String)

    @Query("DELETE FROM lists WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Real delete, not the LWW tombstone (A9: leaving a shared list) — never queued for sync. */
    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun hardDelete(id: String)
}
