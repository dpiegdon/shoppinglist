package org.p23q.shoppinglist.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemDao
import org.p23q.shoppinglist.core.db.ListDao
import org.p23q.shoppinglist.core.db.LwwOptionalString
import org.p23q.shoppinglist.core.db.LwwString
import javax.inject.Singleton

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

/** Adds lists.kind (T-110), an @Embedded LwwString triple. Existing lists default to "shopping"
 *  with clock 0, so they behave exactly as before and any explicit write wins the LWW compare. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lists ADD COLUMN kind_value TEXT NOT NULL DEFAULT 'shopping'")
        db.execSQL("ALTER TABLE lists ADD COLUMN kind_updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lists ADD COLUMN kind_updatedBy TEXT NOT NULL DEFAULT ''")
    }
}

/** Expense lists (T-151, T-152): items.expense_* and lists.currency_* are @Embedded LWW triples;
 *  the roster and vote columns are plain mirrors of server-maintained values, so they carry no
 *  clock and default to "nothing known yet" for every existing row. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN expense_value TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN expense_updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN expense_updatedBy TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE lists ADD COLUMN currency_value TEXT")
        db.execSQL("ALTER TABLE lists ADD COLUMN currency_updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lists ADD COLUMN currency_updatedBy TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE lists ADD COLUMN membersJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE lists ADD COLUMN closeVotesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE lists ADD COLUMN closedAt INTEGER")
    }
}

/** Adds lists.syncBlocked (T-198), the list-row twin of items.syncBlocked: a list the server
 *  refused with a 422 is parked rather than pushed forever. Existing rows default to not blocked. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lists ADD COLUMN syncBlocked INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds items.syncBlockedCode and items.syncBlockedAccountId (T-200): what the server said when it
 *  refused the row, kept beside the quarantine flag so the row can show the reason. Existing
 *  quarantined rows arrive with no reason, which the screens render as the bare "not saved" mark. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN syncBlockedCode TEXT")
        db.execSQL("ALTER TABLE items ADD COLUMN syncBlockedAccountId TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDb(@ApplicationContext context: Context): AppDb =
        Room.databaseBuilder(context, AppDb::class.java, "shoppinglist.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8,
            )
            .build()

    @Provides
    fun provideListDao(db: AppDb): ListDao = db.listDao()

    @Provides
    fun provideItemDao(db: AppDb): ItemDao = db.itemDao()
}
