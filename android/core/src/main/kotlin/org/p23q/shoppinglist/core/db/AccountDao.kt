package org.p23q.shoppinglist.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Used by [org.p23q.shoppinglist.core.account.AccountRegistry] only, the table's one writer. */
@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, rowid")
    suspend fun all(): List<AccountEntity>

    /**
     * An upsert, not an INSERT OR REPLACE: REPLACE deletes the old row first, and lists reference
     * this table, so a replace is a delete of a row that still has children.
     */
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}
