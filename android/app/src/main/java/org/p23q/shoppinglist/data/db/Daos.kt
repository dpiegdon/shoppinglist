package org.p23q.shoppinglist.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /** One-shot (non-Flow) variant, for bulk ops like clear-checked that read the current set once (T-35). */
    @Query("SELECT * FROM items WHERE listId = :listId AND status_value = :status AND deleted_value = 0")
    suspend fun itemsForListByStatusOnce(listId: String, status: String): List<ItemEntity>

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

    /** Rows to push: dirty AND not quarantined by a prior server 422 (T-32). */
    @Query("SELECT * FROM items WHERE dirty = 1 AND syncBlocked = 0")
    suspend fun dirtyRows(): List<ItemEntity>

    @Query("UPDATE items SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    /** Quarantine a row the server rejected (T-32); dirtyRows() then skips it until it's re-edited. */
    @Query("UPDATE items SET syncBlocked = 1 WHERE id = :id")
    suspend fun blockRow(id: String)

    @Query("SELECT COUNT(*) FROM items WHERE syncBlocked = 1")
    suspend fun blockedRowCount(): Int

    /** A quarantined row, so the sync-health surface can send the user to the list that holds it (T-47). */
    @Query("SELECT * FROM items WHERE syncBlocked = 1 LIMIT 1")
    suspend fun firstBlockedItem(): ItemEntity?

    /** Real delete, not the LWW tombstone (A9: leaving a shared list) — never queued for sync. */
    @Query("DELETE FROM items WHERE listId = :listId")
    suspend fun hardDeleteByListId(listId: String)
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

    @Query("SELECT * FROM lists WHERE deleted_value = 0 ORDER BY name_value COLLATE NOCASE")
    fun activeLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE dirty = 1")
    suspend fun dirtyRows(): List<ListEntity>

    @Query("UPDATE lists SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    /** Real delete, not the LWW tombstone (A9: leaving a shared list) — never queued for sync. */
    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun hardDelete(id: String)
}
