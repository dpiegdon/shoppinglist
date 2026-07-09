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
}
