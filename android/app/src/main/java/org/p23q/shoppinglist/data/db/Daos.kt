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

    @Query("SELECT * FROM items WHERE dirty = 1")
    suspend fun dirtyRows(): List<ItemEntity>

    @Query("UPDATE items SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

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

    @Query("SELECT * FROM lists WHERE deleted_value = 0")
    fun activeLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE dirty = 1")
    suspend fun dirtyRows(): List<ListEntity>

    @Query("UPDATE lists SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    /** Real delete, not the LWW tombstone (A9: leaving a shared list) — never queued for sync. */
    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun hardDelete(id: String)
}
