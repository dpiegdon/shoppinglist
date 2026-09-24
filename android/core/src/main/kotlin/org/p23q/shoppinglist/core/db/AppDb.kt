package org.p23q.shoppinglist.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ListEntity::class, ItemEntity::class], version = 8, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun listDao(): ListDao
    abstract fun itemDao(): ItemDao
}
