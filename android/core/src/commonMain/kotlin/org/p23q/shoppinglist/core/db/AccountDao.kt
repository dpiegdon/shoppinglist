package org.p23q.shoppinglist.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Used by [org.p23q.shoppinglist.core.account.AccountRegistry] only, the table's one writer. */
@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, rowid")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    /**
     * Fails on any conflict, the unique (serverUrl, accountId) index included. Not an INSERT OR
     * REPLACE: REPLACE deletes the old row first, and lists reference this table. Not Room's
     * `@Upsert` either: that turns a conflict on the unique index into an UPDATE by primary key,
     * which matches nothing when the primary key is new, so the row is dropped without a word.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity)

    /** By primary key; fails on a conflict with the unique index. Returns the rows changed. */
    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(account: AccountEntity): Int

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}
