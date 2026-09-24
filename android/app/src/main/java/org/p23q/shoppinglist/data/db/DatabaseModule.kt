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
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.normalizeServerUrl
import org.p23q.shoppinglist.core.account.serverLabel
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemDao
import org.p23q.shoppinglist.core.db.ListDao
import org.p23q.shoppinglist.data.LegacySessionSource
import java.util.UUID
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

/**
 * Accounts (T-291). Creates the `accounts` table and gives every list an `accountId`, turning the
 * single-session app's state into the one account row it describes.
 *
 * If the old session holds an account id, or only the id of the account whose lists survived a
 * logout (`mirrorAccountId`), or a token, one `server` row is inserted from it and the old
 * ServerConfig: server URL, cursor, currency, ignored invites and the certificate opt-in move onto
 * it, `signedIn` is whether a token exists, and the token is stored again under the row's id. A
 * database that holds lists but none of that still gets a row, signed out, so that every list has
 * an owner; the next login replaces it as it would have wiped the mirror (T-260). With neither
 * there are no lists and nothing is inserted.
 *
 * An item whose list is gone gets a stub list, owned by that account (T-298). 3.1.0's logout and
 * its full-resync re-base deleted every clean list, including one that still held an unpushed
 * item; items reach their account only through their list, so such an item would belong to no
 * account at all: never pushed, never counted, never removed with an account, and sent under
 * whichever account later pulled the list. The stub is clean, every clock is 0 and it is deleted
 * at clock 0, so it stays hidden until a pull of the real list revives it with the real values.
 *
 * `lists` is rebuilt rather than altered: SQLite cannot add a NOT NULL foreign-key column in place.
 * The old keys are deleted only after the database has opened ([LegacySessionSource.discard]), so a
 * migration that fails and rolls back finds them again next time.
 */
class Migration8To9(private val legacy: LegacySessionSource) : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_ACCOUNTS)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_serverUrl_accountId` " +
                "ON `accounts` (`serverUrl`, `accountId`)",
        )

        val session = legacy.read()
        val owner = session.accountId ?: session.mirrorAccountId
        val hasSession = owner != null || session.token != null
        val localId = UUID.randomUUID().toString()
        val serverUrl = session.serverUrl?.let(::normalizeServerUrl)
        db.execSQL(
            "INSERT INTO `accounts` (`id`, `kind`, `serverUrl`, `accountId`, `email`, `isAdmin`, `label`, " +
                "`signedIn`, `outdated`, `serverProtocol`, `syncCursor`, `defaultCurrency`, " +
                "`ignoredInviteIdsJson`, `allowSelfSignedCerts`, `sortOrder`) " +
                "SELECT ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, 0 " +
                "WHERE ? OR EXISTS (SELECT 1 FROM `lists`) OR EXISTS (SELECT 1 FROM `items`)",
            arrayOf<Any?>(
                localId,
                AccountEntity.KIND_SERVER,
                serverUrl,
                owner,
                session.email,
                if (session.isAdmin) 1L else 0L,
                serverUrl?.let(::serverLabel) ?: "",
                if (session.token != null) 1L else 0L,
                session.syncCursor,
                session.defaultCurrency,
                AccountRegistry.encodeIds(session.ignoredInviteIds),
                if (session.allowSelfSignedCerts) 1L else 0L,
                if (hasSession) 1L else 0L,
            ),
        )

        db.execSQL(CREATE_LISTS.replace("`lists`", "`lists_new`"))
        db.execSQL(
            "INSERT INTO `lists_new` (`accountId`, $LIST_COLUMNS) SELECT ?, $LIST_COLUMNS FROM `lists`",
            arrayOf<Any?>(localId),
        )
        db.execSQL("DROP TABLE `lists`")
        db.execSQL("ALTER TABLE `lists_new` RENAME TO `lists`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lists_accountId` ON `lists` (`accountId`)")
        db.execSQL(INSERT_ORPHAN_STUBS, arrayOf<Any?>(localId))

        if (session.token != null) legacy.adoptToken(localId)
    }

    private companion object {
        /** Room's own SQL, from core/schemas/…/9.json. */
        const val CREATE_ACCOUNTS =
            "CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`serverUrl` TEXT, `accountId` TEXT, `email` TEXT, `isAdmin` INTEGER NOT NULL, " +
                "`label` TEXT NOT NULL, `signedIn` INTEGER NOT NULL, `outdated` INTEGER NOT NULL, " +
                "`serverProtocol` INTEGER, `syncCursor` INTEGER NOT NULL, `defaultCurrency` TEXT, " +
                "`ignoredInviteIdsJson` TEXT NOT NULL, `allowSelfSignedCerts` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val CREATE_LISTS =
            "CREATE TABLE IF NOT EXISTS `lists` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `syncBlocked` INTEGER NOT NULL, " +
                "`membersJson` TEXT NOT NULL, `closeVotesJson` TEXT NOT NULL, `closedAt` INTEGER, " +
                "`name_value` TEXT NOT NULL, `name_updatedAt` INTEGER NOT NULL, `name_updatedBy` TEXT NOT NULL, " +
                "`categoryOrder_value` TEXT NOT NULL, `categoryOrder_updatedAt` INTEGER NOT NULL, " +
                "`categoryOrder_updatedBy` TEXT NOT NULL, `notes_value` TEXT, `notes_updatedAt` INTEGER NOT NULL, " +
                "`notes_updatedBy` TEXT NOT NULL, `kind_value` TEXT NOT NULL, `kind_updatedAt` INTEGER NOT NULL, " +
                "`kind_updatedBy` TEXT NOT NULL, `currency_value` TEXT, `currency_updatedAt` INTEGER NOT NULL, " +
                "`currency_updatedBy` TEXT NOT NULL, `deleted_value` INTEGER NOT NULL, " +
                "`deleted_updatedAt` INTEGER NOT NULL, `deleted_updatedBy` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"

        /** Every column of schema 8's `lists`, which schema 9 keeps unchanged. */
        const val LIST_COLUMNS =
            "`id`, `createdAt`, `dirty`, `syncBlocked`, `membersJson`, `closeVotesJson`, `closedAt`, " +
                "`name_value`, `name_updatedAt`, `name_updatedBy`, `categoryOrder_value`, " +
                "`categoryOrder_updatedAt`, `categoryOrder_updatedBy`, `notes_value`, `notes_updatedAt`, " +
                "`notes_updatedBy`, `kind_value`, `kind_updatedAt`, `kind_updatedBy`, `currency_value`, " +
                "`currency_updatedAt`, `currency_updatedBy`, `deleted_value`, `deleted_updatedAt`, " +
                "`deleted_updatedBy`"

        /** One stub list for every list id items name that has no row; see the class comment. */
        const val INSERT_ORPHAN_STUBS =
            "INSERT INTO `lists` (`accountId`, $LIST_COLUMNS) " +
                "SELECT DISTINCT ?, `listId`, 0, 0, 0, '[]', '[]', NULL, " +
                "'', 0, '', '[]', 0, '', NULL, 0, '', 'shopping', 0, '', NULL, 0, '', 1, 0, '' " +
                "FROM `items` WHERE `listId` NOT IN (SELECT `id` FROM `lists`)"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDb(@ApplicationContext context: Context, legacy: LegacySessionSource): AppDb =
        Room.databaseBuilder(context, AppDb::class.java, "shoppinglist.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, Migration8To9(legacy),
            )
            .build()

    @Provides
    fun provideListDao(db: AppDb): ListDao = db.listDao()

    @Provides
    fun provideItemDao(db: AppDb): ItemDao = db.itemDao()
}
