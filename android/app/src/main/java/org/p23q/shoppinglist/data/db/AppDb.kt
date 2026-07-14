package org.p23q.shoppinglist.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [ListEntity::class, ItemEntity::class], version = 4, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun listDao(): ListDao
    abstract fun itemDao(): ItemDao
}

/** Adds items.syncBlocked (T-32 row quarantine). Non-destructive: existing rows keep their data. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN syncBlocked INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds lists.notes (T-62), an @Embedded LwwOptionalString — same three-column shape Room already
 *  generates for it elsewhere (e.g. items.note_*): value/updatedAt/updatedBy. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lists ADD COLUMN notes_value TEXT")
        db.execSQL("ALTER TABLE lists ADD COLUMN notes_updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lists ADD COLUMN notes_updatedBy TEXT NOT NULL DEFAULT ''")
    }
}

/** Adds items.lastTouchedByAccountId (T-64) — a plain nullable column, not an @Embedded LWW
 *  triple: the client never writes it locally, it only mirrors whatever the server last reported. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN lastTouchedByAccountId TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDb(@ApplicationContext context: Context): AppDb =
        Room.databaseBuilder(context, AppDb::class.java, "shoppinglist.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideListDao(db: AppDb): ListDao = db.listDao()

    @Provides
    fun provideItemDao(db: AppDb): ItemDao = db.itemDao()
}
