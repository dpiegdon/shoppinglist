package org.p23q.shoppinglist.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, ListEntity::class, ItemEntity::class],
    version = 10,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun listDao(): ListDao
    abstract fun itemDao(): ItemDao
}
