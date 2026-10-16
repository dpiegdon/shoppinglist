package org.p23q.shoppinglist.core.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [AccountEntity::class, ListEntity::class, ItemEntity::class],
    version = 13,
    exportSchema = true,
)
@ConstructedBy(AppDbConstructor::class)
abstract class AppDb : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun listDao(): ListDao
    abstract fun itemDao(): ItemDao
}

/** How Room builds [AppDb] where it cannot look the implementation up by reflection; Room generates the actuals. */
@Suppress("KotlinNoActualForExpect")
expect object AppDbConstructor : RoomDatabaseConstructor<AppDb> {
    override fun initialize(): AppDb
}
