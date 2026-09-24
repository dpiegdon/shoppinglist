package org.p23q.shoppinglist.core.db

import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Empties every table and hands the freed pages back to the file system: what Android's
 * `RoomDatabase.clearAllTables()` does (delete in one transaction, then checkpoint and VACUUM).
 *
 * That method exists only in Room's Android artifact. [AppDb] is compiled here for the JVM, where
 * Room generates no implementation of it, so calling it from :app compiles and then fails at run
 * time with AbstractMethodError. Use this instead.
 *
 * Must not be called inside [inTransaction]: VACUUM cannot run in a transaction.
 */
suspend fun AppDb.clearAll() {
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            execSQL("DELETE FROM `lists`")
            execSQL("DELETE FROM `items`")
        }
        transactor.execSQL("PRAGMA wal_checkpoint(FULL)")
        transactor.execSQL("VACUUM")
    }
}
