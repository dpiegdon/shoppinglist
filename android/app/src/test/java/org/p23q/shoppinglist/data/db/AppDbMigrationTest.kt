package org.p23q.shoppinglist.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.room.migration.Migration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import org.p23q.shoppinglist.core.db.unblocked
import org.p23q.shoppinglist.data.LegacySession
import org.p23q.shoppinglist.data.LegacySessionSource

/**
 * The migrations run against a real old database, and land on the schema Room expects (T-162).
 *
 * Every other test in this module builds its database in memory at the CURRENT version, which is
 * exactly the case where no migration executes — so before this file, MIGRATION_5_6 had never run
 * anywhere, on a device or otherwise. A broken migration is not a failed query: Room validates the
 * migrated schema while opening the database and throws if it does not match the entities, which on
 * this app means a crash on launch for everyone upgrading, on every launch, until they clear the
 * app's data.
 *
 * ## Why this does not simply open a Room database
 *
 * Neither half of the real path is reachable on this hardware:
 *
 * - The framework path (what DatabaseModule uses, and what runs on a phone) needs Robolectric's
 *   SQLite. Both of its implementations lack Linux/aarch64 binaries, which is why every test here
 *   uses BundledSQLiteDriver and why robolectric.properties pins LEGACY.
 * - The driver path cannot run migrations at all: Room 2.8's Migration.migrate(SQLiteConnection)
 *   is `TODO("Migration functionality with a SQLiteDriver is not yet available")` for anything but
 *   a support-backed connection. **The production builder must therefore not adopt setDriver()
 *   until Room implements this** — doing so would break every migration at once.
 *
 * So the migration objects are driven directly, against a database built at the old version, and
 * the result is compared against the schema Room itself exported. That comparison is the same one
 * Room performs at runtime: column name, type affinity and nullability, per table. What is not
 * covered is Room's own open-and-validate machinery, which is not this project's code.
 */
class AppDbMigrationTest {

    /**
     * Version 1: the schema before any migration, taken from core/schemas/…/8.json minus every
     * column the later migrations add — so it is Room's own SQL, not a hand-written guess at it.
     */
    private val v1Lists =
        "CREATE TABLE IF NOT EXISTS `lists` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`dirty` INTEGER NOT NULL, `name_value` TEXT NOT NULL, `name_updatedAt` INTEGER NOT NULL, " +
            "`name_updatedBy` TEXT NOT NULL, `categoryOrder_value` TEXT NOT NULL, " +
            "`categoryOrder_updatedAt` INTEGER NOT NULL, `categoryOrder_updatedBy` TEXT NOT NULL, " +
            "`deleted_value` INTEGER NOT NULL, `deleted_updatedAt` INTEGER NOT NULL, " +
            "`deleted_updatedBy` TEXT NOT NULL, PRIMARY KEY(`id`))"

    private val v1Items =
        "CREATE TABLE IF NOT EXISTS `items` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `name_value` TEXT NOT NULL, " +
            "`name_updatedAt` INTEGER NOT NULL, `name_updatedBy` TEXT NOT NULL, `category_value` TEXT, " +
            "`category_updatedAt` INTEGER NOT NULL, `category_updatedBy` TEXT NOT NULL, " +
            "`stores_value` TEXT NOT NULL, `stores_updatedAt` INTEGER NOT NULL, " +
            "`stores_updatedBy` TEXT NOT NULL, `quantity_value` TEXT, " +
            "`quantity_updatedAt` INTEGER NOT NULL, `quantity_updatedBy` TEXT NOT NULL, " +
            "`price_value` TEXT, `price_updatedAt` INTEGER NOT NULL, `price_updatedBy` TEXT NOT NULL, " +
            "`note_value` TEXT, `note_updatedAt` INTEGER NOT NULL, `note_updatedBy` TEXT NOT NULL, " +
            "`status_value` TEXT NOT NULL, `status_updatedAt` INTEGER NOT NULL, " +
            "`status_updatedBy` TEXT NOT NULL, `deleted_value` INTEGER NOT NULL, " +
            "`deleted_updatedAt` INTEGER NOT NULL, `deleted_updatedBy` TEXT NOT NULL, PRIMARY KEY(`id`))"

    /** What the single-session stores hold, as a test sets it; records which id got the token. */
    private class FakeLegacySession(private val session: LegacySession = LegacySession()) : LegacySessionSource {
        var adoptedBy: String? = null
        override fun read(): LegacySession = session
        override fun adoptToken(localAccountId: String) {
            adoptedBy = localAccountId
        }
        override suspend fun discard() {}
    }

    private val migrations = listOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, Migration8To9(FakeLegacySession()), MIGRATION_9_10,
    )

    private fun execWithArgs(connection: SQLiteConnection, sql: String, bindArgs: Array<*>) {
        connection.prepare(sql).use { statement ->
            bindArgs.forEachIndexed { i, arg ->
                when (arg) {
                    null -> statement.bindNull(i + 1)
                    is String -> statement.bindText(i + 1, arg)
                    is Long -> statement.bindLong(i + 1, arg)
                    is Int -> statement.bindLong(i + 1, arg.toLong())
                    is Boolean -> statement.bindLong(i + 1, if (arg) 1L else 0L)
                    else -> throw UnsupportedOperationException("bind argument of type ${arg::class}")
                }
            }
            statement.step()
        }
    }

    /**
     * Hands a migration something that looks like a SupportSQLiteDatabase and forwards the only
     * method migrations use: execSQL, with or without bind arguments. Anything else fails loudly rather than silently doing nothing, so a
     * future migration that reaches for a query or a transaction cannot pass this test by accident.
     */
    private fun supportFacade(connection: SQLiteConnection): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when {
                method.name == "execSQL" && args.size == 1 -> connection.execSQL(args[0] as String)
                method.name == "execSQL" && args.size == 2 -> execWithArgs(connection, args[0] as String, args[1] as Array<*>)
                else -> throw UnsupportedOperationException(
                    "this facade forwards execSQL only; a migration now calls ${method.name}",
                )
            }
        } as SupportSQLiteDatabase

    private fun openFresh(name: String): SQLiteConnection {
        val file = File(System.getProperty("java.io.tmpdir"), "t162-$name.db")
        file.delete()
        file.deleteOnExit()
        return BundledSQLiteDriver().open(file.absolutePath)
    }

    /** A version-1 database with one list and one item in it. */
    private fun seedV1(connection: SQLiteConnection) {
        connection.execSQL(v1Lists)
        connection.execSQL(v1Items)
        connection.execSQL(
            "INSERT INTO lists (id, createdAt, dirty, name_value, name_updatedAt, name_updatedBy, " +
                "categoryOrder_value, categoryOrder_updatedAt, categoryOrder_updatedBy, " +
                "deleted_value, deleted_updatedAt, deleted_updatedBy) " +
                "VALUES ('l1', 10, 0, 'Groceries', 11, 'devA', '[]', 0, '', 0, 0, '')",
        )
        connection.execSQL(
            "INSERT INTO items (id, listId, createdAt, dirty, name_value, name_updatedAt, " +
                "name_updatedBy, category_updatedAt, category_updatedBy, stores_value, " +
                "stores_updatedAt, stores_updatedBy, quantity_updatedAt, quantity_updatedBy, " +
                "price_updatedAt, price_updatedBy, note_updatedAt, note_updatedBy, status_value, " +
                "status_updatedAt, status_updatedBy, deleted_value, deleted_updatedAt, " +
                "deleted_updatedBy) " +
                "VALUES ('i1', 'l1', 10, 0, 'Milk', 11, 'devA', 0, '', '[]', 0, '', 0, '', 0, '', " +
                "0, '', 'todo', 11, 'devA', 0, 0, '')",
        )
    }

    private fun runMigrations(connection: SQLiteConnection, from: Int, to: Int) {
        val facade = supportFacade(connection)
        migrations.filter { it.startVersion >= from && it.endVersion <= to }
            .sortedBy(Migration::startVersion)
            .forEach { it.migrate(facade) }
    }

    /** One row of PRAGMA table_info, in the terms the exported schema uses. */
    private data class Column(val name: String, val affinity: String, val notNull: Boolean)

    private fun actualColumns(connection: SQLiteConnection, table: String): Set<Column> {
        val statement = connection.prepare("PRAGMA table_info($table)")
        val columns = mutableSetOf<Column>()
        statement.use {
            while (it.step()) {
                columns += Column(it.getText(1), it.getText(2).uppercase(), it.getLong(3) == 1L)
            }
        }
        return columns
    }

    /** What Room says [version] must look like — its own exported schema, committed alongside. */
    private fun exportedEntity(table: String, version: Int) = File("../core/schemas/org.p23q.shoppinglist.core.db.AppDb/$version.json").let { file ->
        assertTrue("exported schema missing — run the ksp task: ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == table }
    }

    private fun expectedColumns(table: String, version: Int = 8): Set<Column> {
        val entity = exportedEntity(table, version)
        return entity["fields"]!!.jsonArray.map { field ->
            val f = field.jsonObject
            Column(
                f["columnName"]!!.jsonPrimitive.content,
                f["affinity"]!!.jsonPrimitive.content.uppercase(),
                // Room omits notNull entirely for a nullable column rather than writing false.
                f["notNull"]?.jsonPrimitive?.content.toBoolean(),
            )
        }.toSet()
    }

    private fun readText(connection: SQLiteConnection, sql: String): String? =
        connection.prepare(sql).use { if (it.step()) (if (it.isNull(0)) null else it.getText(0)) else null }

    private fun readLong(connection: SQLiteConnection, sql: String): Long? =
        connection.prepare(sql).use { if (it.step()) (if (it.isNull(0)) null else it.getLong(0)) else null }

    @Test
    fun `migrating 7 to 8 lands on exactly the schema Room expects`() {
        val connection = openFresh("v7")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 7)
            // Only the step under test from here, on top of everything that came before it.
            MIGRATION_7_8.migrate(supportFacade(connection))

            assertEquals(expectedColumns("lists"), actualColumns(connection, "lists"))
            assertEquals(expectedColumns("items"), actualColumns(connection, "items"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 7 to 8 keeps the rows and leaves the refusal columns empty`() {
        val connection = openFresh("v7-rows")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 7)
            MIGRATION_7_8.migrate(supportFacade(connection))

            assertEquals("Milk", readText(connection, "SELECT name_value FROM items"))
            // A row parked before these columns existed has no stored refusal (T-200), and must
            // arrive saying nothing rather than inventing a code the screens would then translate.
            assertNull(readText(connection, "SELECT syncBlockedCode FROM items"))
            assertNull(readText(connection, "SELECT syncBlockedAccountId FROM items"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 6 to 7 keeps the rows and leaves every list unblocked`() {
        val connection = openFresh("v6-rows")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 6)
            MIGRATION_6_7.migrate(supportFacade(connection))

            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists"))
            // A list that existed before the quarantine column must arrive pushable (T-198), not
            // parked — lists.dirtyRows() skips a blocked row, so a 1 here would silently stop sync.
            assertEquals(0L, readLong(connection, "SELECT syncBlocked FROM lists"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 5 to 6 keeps the rows and defaults the new columns to nothing known`() {
        val connection = openFresh("v5-rows")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 5)
            MIGRATION_5_6.migrate(supportFacade(connection))

            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists"))
            assertEquals("Milk", readText(connection, "SELECT name_value FROM items"))
            assertEquals("shopping", readText(connection, "SELECT kind_value FROM lists"))

            // The T-151/T-152 columns must arrive at "nothing known yet" rather than at something
            // that reads as a real value: no currency, an empty roster, and open.
            assertNull(readText(connection, "SELECT currency_value FROM lists"))
            assertEquals(0L, readLong(connection, "SELECT currency_updatedAt FROM lists"))
            assertEquals("[]", readText(connection, "SELECT membersJson FROM lists"))
            assertEquals("[]", readText(connection, "SELECT closeVotesJson FROM lists"))
            assertNull(readLong(connection, "SELECT closedAt FROM lists"))
            assertNull(readText(connection, "SELECT expense_value FROM items"))
            assertEquals(0L, readLong(connection, "SELECT expense_updatedAt FROM items"))
            assertEquals("", readText(connection, "SELECT expense_updatedBy FROM items"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 1 to 8 lands on the same schema and keeps the rows`() {
        val connection = openFresh("v1")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 8)

            assertEquals(expectedColumns("lists"), actualColumns(connection, "lists"))
            assertEquals(expectedColumns("items"), actualColumns(connection, "items"))
            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists"))
            assertEquals("Milk", readText(connection, "SELECT name_value FROM items"))
            // Each intermediate migration's default survived the ones that came after it.
            assertNull(readText(connection, "SELECT notes_value FROM lists"))
            assertEquals("shopping", readText(connection, "SELECT kind_value FROM lists"))
            assertEquals(0L, readLong(connection, "SELECT syncBlocked FROM items"))
            assertEquals(0L, readLong(connection, "SELECT syncBlocked FROM lists"))
            assertNull(readText(connection, "SELECT lastTouchedByAccountId FROM items"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the migration list covers every version up to the one the database declares`() {
        // Guards the gap this ticket came from: a version bump with no matching migration, or an
        // exported schema left stale by a build that was never re-run. @Database is compile-time
        // retained, so the declared version is read from the schema Room exported from it — the
        // same file the other tests here compare against.
        val exported = File("../core/schemas/org.p23q.shoppinglist.core.db.AppDb/$LATEST.json")
        assertTrue("exported schema missing: ${exported.absolutePath}", exported.exists())
        val declared = Json.parseToJsonElement(exported.readText())
            .jsonObject["database"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt()

        assertEquals(declared, migrations.maxOf(Migration::endVersion))
        // One migration per step, no gaps: a version bump with no migration strands every upgrade.
        assertEquals((2..declared).toList(), migrations.map(Migration::endVersion).sorted())
    }

    // ---- 8 to 9: accounts (T-291) ------------------------------------------------------

    /** A database at version 8 with one list and one item, as the single-session app left it. */
    private fun seedV8(connection: SQLiteConnection) {
        seedV1(connection)
        runMigrations(connection, from = 1, to = 8)
    }

    /** Unique indices and foreign keys, which Room validates on open as it does the columns. */
    private fun actualIndices(connection: SQLiteConnection, table: String): Set<Pair<String, Boolean>> {
        val indices = mutableSetOf<Pair<String, Boolean>>()
        connection.prepare("PRAGMA index_list($table)").use {
            while (it.step()) {
                val name = it.getText(1)
                if (!name.startsWith("sqlite_autoindex")) indices += name to (it.getLong(2) == 1L)
            }
        }
        return indices
    }

    private fun expectedIndices(table: String, version: Int = 9): Set<Pair<String, Boolean>> =
        exportedEntity(table, version)["indices"]?.jsonArray.orEmpty().map { index ->
            val i = index.jsonObject
            i["name"]!!.jsonPrimitive.content to i["unique"]!!.jsonPrimitive.content.toBoolean()
        }.toSet()

    private fun actualForeignKeys(connection: SQLiteConnection, table: String): Set<Triple<String, String, String>> {
        val keys = mutableSetOf<Triple<String, String, String>>()
        connection.prepare("PRAGMA foreign_key_list($table)").use {
            while (it.step()) keys += Triple(it.getText(2), it.getText(3), it.getText(4))
        }
        return keys
    }

    private fun expectedForeignKeys(table: String, version: Int = 9): Set<Triple<String, String, String>> =
        exportedEntity(table, version)["foreignKeys"]?.jsonArray.orEmpty().flatMap { key ->
            val k = key.jsonObject
            val columns = k["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
            val referenced = k["referencedColumns"]!!.jsonArray.map { it.jsonPrimitive.content }
            columns.zip(referenced).map { (from, to) -> Triple(k["table"]!!.jsonPrimitive.content, from, to) }
        }.toSet()

    private fun count(connection: SQLiteConnection, sql: String): Long = readLong(connection, sql)!!

    private val signedIn = LegacySession(
        token = "tok-legacy",
        accountId = "acc-1",
        mirrorAccountId = "acc-1",
        email = "milk@example.com",
        isAdmin = true,
        defaultCurrency = "EUR",
        syncCursor = 812,
        ignoredInviteIds = setOf("inv-2", "inv-1"),
        serverUrl = "https://lists.example.com/shopping",
        allowSelfSignedCerts = true,
    )

    @Test
    fun `migrating 8 to 9 lands on exactly the schema Room expects`() {
        val connection = openFresh("v8")
        try {
            seedV8(connection)
            Migration8To9(FakeLegacySession(signedIn)).migrate(supportFacade(connection))

            for (table in listOf("accounts", "lists", "items")) {
                assertEquals(table, expectedColumns(table, 9), actualColumns(connection, table))
                assertEquals(table, expectedIndices(table), actualIndices(connection, table))
                assertEquals(table, expectedForeignKeys(table), actualForeignKeys(connection, table))
            }
            // Room runs this after migrating a schema with foreign keys; every list must have its owner.
            connection.prepare("PRAGMA foreign_key_check").use { assertTrue("no dangling accountId", !it.step()) }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 8 to 9 turns a signed-in session into one signed-in account that owns every list`() {
        val connection = openFresh("v8-signed-in")
        val legacy = FakeLegacySession(signedIn)
        try {
            seedV8(connection)
            Migration8To9(legacy).migrate(supportFacade(connection))

            assertEquals(1L, count(connection, "SELECT COUNT(*) FROM accounts"))
            val id = readText(connection, "SELECT id FROM accounts")!!
            assertNotEquals("a local id, never the server's", "acc-1", id)
            assertEquals("server", readText(connection, "SELECT kind FROM accounts"))
            assertEquals("https://lists.example.com/shopping/", readText(connection, "SELECT serverUrl FROM accounts"))
            assertEquals("lists.example.com", readText(connection, "SELECT label FROM accounts"))
            assertEquals("acc-1", readText(connection, "SELECT accountId FROM accounts"))
            assertEquals("milk@example.com", readText(connection, "SELECT email FROM accounts"))
            assertEquals(1L, readLong(connection, "SELECT isAdmin FROM accounts"))
            assertEquals(1L, readLong(connection, "SELECT signedIn FROM accounts"))
            assertEquals(0L, readLong(connection, "SELECT outdated FROM accounts"))
            assertNull(readLong(connection, "SELECT serverProtocol FROM accounts"))
            assertEquals(812L, readLong(connection, "SELECT syncCursor FROM accounts"))
            assertEquals("EUR", readText(connection, "SELECT defaultCurrency FROM accounts"))
            assertEquals("""["inv-1","inv-2"]""", readText(connection, "SELECT ignoredInviteIdsJson FROM accounts"))
            assertEquals(1L, readLong(connection, "SELECT allowSelfSignedCerts FROM accounts"))

            assertEquals(id, readText(connection, "SELECT accountId FROM lists WHERE id = 'l1'"))
            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists"))
            assertEquals("Milk", readText(connection, "SELECT name_value FROM items"))
            assertEquals("the token moves under the new id", id, legacy.adoptedBy)
        } finally {
            connection.close()
        }
    }

    /** T-257/T-260: logged out with unpushed rows left behind — only the mirror's owner is known. */
    @Test
    fun `migrating 8 to 9 keeps a signed-out mirror as a signed-out account`() {
        val connection = openFresh("v8-signed-out")
        val legacy = FakeLegacySession(
            LegacySession(mirrorAccountId = "acc-1", serverUrl = "https://lists.example.com/"),
        )
        try {
            seedV8(connection)
            connection.execSQL("UPDATE items SET dirty = 1")
            Migration8To9(legacy).migrate(supportFacade(connection))

            assertEquals(1L, count(connection, "SELECT COUNT(*) FROM accounts"))
            assertEquals("acc-1", readText(connection, "SELECT accountId FROM accounts"))
            assertEquals("signed out: there is no token", 0L, readLong(connection, "SELECT signedIn FROM accounts"))
            assertEquals(0L, readLong(connection, "SELECT syncCursor FROM accounts"))
            val id = readText(connection, "SELECT id FROM accounts")
            assertEquals(id, readText(connection, "SELECT accountId FROM lists"))
            assertEquals("the unpushed row survives", 1L, readLong(connection, "SELECT dirty FROM items"))
            assertNull("no token to move", legacy.adoptedBy)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 8 to 9 on a fresh install inserts nothing`() {
        val connection = openFresh("v8-fresh")
        val legacy = FakeLegacySession()
        try {
            connection.execSQL(v1Lists)
            connection.execSQL(v1Items)
            runMigrations(connection, from = 1, to = 8)
            Migration8To9(legacy).migrate(supportFacade(connection))

            assertEquals(0L, count(connection, "SELECT COUNT(*) FROM accounts"))
            assertEquals(0L, count(connection, "SELECT COUNT(*) FROM lists"))
            assertNull(legacy.adoptedBy)
        } finally {
            connection.close()
        }
    }

    /** An item on list [listId], which the test may leave without a list row. */
    private fun insertItem(connection: SQLiteConnection, id: String, listId: String, dirty: Boolean) {
        connection.execSQL(
            "INSERT INTO items (id, listId, createdAt, dirty, name_value, name_updatedAt, " +
                "name_updatedBy, category_updatedAt, category_updatedBy, stores_value, " +
                "stores_updatedAt, stores_updatedBy, quantity_updatedAt, quantity_updatedBy, " +
                "price_updatedAt, price_updatedBy, note_updatedAt, note_updatedBy, status_value, " +
                "status_updatedAt, status_updatedBy, deleted_value, deleted_updatedAt, " +
                "deleted_updatedBy) " +
                "VALUES ('$id', '$listId', 10, ${if (dirty) 1 else 0}, 'Eggs', 12, 'devA', 0, '', '[]', 0, '', " +
                "0, '', 0, '', 0, '', 'todo', 12, 'devA', 0, 0, '')",
        )
    }

    /**
     * T-298: 3.1.0 deleted a clean list on logout even when it still held an unpushed item. Without
     * a list such an item reaches no account, so it would never be pushed, counted or removed.
     */
    @Test
    fun `migrating 8 to 9 gives an item whose list is gone a hidden stub list of the account`() {
        val connection = openFresh("v8-orphan")
        try {
            seedV8(connection)
            insertItem(connection, "orphan-1", listId = "gone", dirty = true)
            insertItem(connection, "orphan-2", listId = "gone", dirty = false)
            Migration8To9(FakeLegacySession(signedIn)).migrate(supportFacade(connection))

            val id = readText(connection, "SELECT id FROM accounts")
            assertEquals(2L, count(connection, "SELECT COUNT(*) FROM lists"))
            assertEquals("owned by the migrated account", id, readText(connection, "SELECT accountId FROM lists WHERE id = 'gone'"))
            assertEquals("nothing to push", 0L, readLong(connection, "SELECT dirty FROM lists WHERE id = 'gone'"))
            assertEquals("hidden", 1L, readLong(connection, "SELECT deleted_value FROM lists WHERE id = 'gone'"))
            assertEquals(
                "every clock 0, so any pull wins every field",
                0L,
                readLong(
                    connection,
                    "SELECT name_updatedAt + categoryOrder_updatedAt + notes_updatedAt + kind_updatedAt + " +
                        "currency_updatedAt + deleted_updatedAt FROM lists WHERE id = 'gone'",
                ),
            )
            assertEquals("shopping", readText(connection, "SELECT kind_value FROM lists WHERE id = 'gone'"))
            assertEquals("", readText(connection, "SELECT name_value FROM lists WHERE id = 'gone'"))
            assertEquals("[]", readText(connection, "SELECT categoryOrder_value FROM lists WHERE id = 'gone'"))
            assertEquals("the item stays unpushed", 1L, readLong(connection, "SELECT dirty FROM items WHERE id = 'orphan-1'"))
            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists WHERE id = 'l1'"))
            connection.prepare("PRAGMA foreign_key_check").use { assertTrue(!it.step()) }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 8 to 9 gives orphan items an owner even when no list and no session is left`() {
        val connection = openFresh("v8-orphan-only")
        try {
            connection.execSQL(v1Lists)
            connection.execSQL(v1Items)
            runMigrations(connection, from = 1, to = 8)
            insertItem(connection, "orphan-1", listId = "gone", dirty = true)
            Migration8To9(FakeLegacySession()).migrate(supportFacade(connection))

            assertEquals(1L, count(connection, "SELECT COUNT(*) FROM accounts"))
            assertEquals(readText(connection, "SELECT id FROM accounts"), readText(connection, "SELECT accountId FROM lists WHERE id = 'gone'"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 8 to 9 gives lists nobody is recorded for a signed-out owner`() {
        // No session and no mirror owner, but lists: nothing should leave a database like this,
        // and if one does, its lists still need an owner for the foreign key. The next login
        // replaces that owner, exactly as it would have wiped an unowned mirror (T-260).
        val connection = openFresh("v8-unowned")
        try {
            seedV8(connection)
            Migration8To9(FakeLegacySession()).migrate(supportFacade(connection))

            assertEquals(1L, count(connection, "SELECT COUNT(*) FROM accounts"))
            assertEquals(0L, readLong(connection, "SELECT signedIn FROM accounts"))
            assertNull(readText(connection, "SELECT accountId FROM accounts"))
            assertEquals(readText(connection, "SELECT id FROM accounts"), readText(connection, "SELECT accountId FROM lists"))
            connection.prepare("PRAGMA foreign_key_check").use { assertTrue(!it.step()) }
        } finally {
            connection.close()
        }
    }

    // ---- 9 to 10: phone-local ids (T-299) ----------------------------------------------

    /** The schema this build's database declares; the newest exported schema. */
    private val LATEST = 10

    /** A version-9 database: [seedV8]'s list and item, owned by the migrated signed-in account. */
    private fun seedV9(connection: SQLiteConnection, orphans: Boolean = false) {
        seedV8(connection)
        if (orphans) insertItem(connection, "orphan-1", listId = "gone", dirty = true)
        Migration8To9(FakeLegacySession(signedIn)).migrate(supportFacade(connection))
    }

    /** A second account at version 9, with list [listId] holding item [itemId]. */
    private fun addV9AccountWithList(connection: SQLiteConnection, accountId: String, listId: String, itemId: String) {
        connection.execSQL(
            "INSERT INTO accounts (id, kind, serverUrl, accountId, email, isAdmin, label, signedIn, outdated, " +
                "serverProtocol, syncCursor, defaultCurrency, ignoredInviteIdsJson, allowSelfSignedCerts, sortOrder) " +
                "VALUES ('$accountId', 'server', 'https://other.example.com/', 'acc-$accountId', NULL, 0, 'other', " +
                "1, 0, NULL, 5, NULL, '[]', 0, 1)",
        )
        connection.execSQL(
            "INSERT INTO lists (id, accountId, createdAt, dirty, syncBlocked, membersJson, closeVotesJson, closedAt, " +
                "name_value, name_updatedAt, name_updatedBy, categoryOrder_value, categoryOrder_updatedAt, " +
                "categoryOrder_updatedBy, notes_value, notes_updatedAt, notes_updatedBy, kind_value, kind_updatedAt, " +
                "kind_updatedBy, currency_value, currency_updatedAt, currency_updatedBy, deleted_value, " +
                "deleted_updatedAt, deleted_updatedBy) " +
                "VALUES ('$listId', '$accountId', 20, 1, 0, '[]', '[]', NULL, 'Hardware', 21, 'devB', '[]', 0, '', " +
                "NULL, 0, '', 'checklist', 21, 'devB', NULL, 0, '', 0, 0, '')",
        )
        insertItem(connection, itemId, listId = listId, dirty = true)
    }

    @Test
    fun `migrating 9 to 10 lands on exactly the schema Room expects`() {
        val connection = openFresh("v9")
        try {
            seedV9(connection)
            MIGRATION_9_10.migrate(supportFacade(connection))

            for (table in listOf("accounts", "lists", "items")) {
                assertEquals(table, expectedColumns(table, 10), actualColumns(connection, table))
                assertEquals(table, expectedIndices(table, 10), actualIndices(connection, table))
                assertEquals(table, expectedForeignKeys(table, 10), actualForeignKeys(connection, table))
            }
            connection.prepare("PRAGMA foreign_key_check").use { assertTrue("no dangling accountId", !it.step()) }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 9 to 10 keeps every row, each under its old id as local and server id, items with their list's account`() {
        val connection = openFresh("v9-two-lists")
        try {
            seedV9(connection)
            addV9AccountWithList(connection, accountId = "acct-2", listId = "l2", itemId = "i2")
            val first = readText(connection, "SELECT id FROM accounts WHERE id != 'acct-2'")!!
            MIGRATION_9_10.migrate(supportFacade(connection))

            assertEquals(2L, count(connection, "SELECT COUNT(*) FROM lists"))
            assertEquals(2L, count(connection, "SELECT COUNT(*) FROM items"))
            for ((list, owner) in listOf("l1" to first, "l2" to "acct-2")) {
                assertEquals(list, readText(connection, "SELECT serverId FROM lists WHERE localId = '$list'"))
                assertEquals(owner, readText(connection, "SELECT accountId FROM lists WHERE localId = '$list'"))
            }
            for ((item, list, owner) in listOf(Triple("i1", "l1", first), Triple("i2", "l2", "acct-2"))) {
                assertEquals(item, readText(connection, "SELECT serverId FROM items WHERE localId = '$item'"))
                assertEquals(list, readText(connection, "SELECT listLocalId FROM items WHERE localId = '$item'"))
                assertEquals("the item's account is its list's", owner, readText(connection, "SELECT accountId FROM items WHERE localId = '$item'"))
            }
            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists WHERE localId = 'l1'"))
            assertEquals("checklist", readText(connection, "SELECT kind_value FROM lists WHERE localId = 'l2'"))
            assertEquals(1L, readLong(connection, "SELECT dirty FROM lists WHERE localId = 'l2'"))
            assertEquals("Milk", readText(connection, "SELECT name_value FROM items WHERE localId = 'i1'"))
            assertEquals(1L, readLong(connection, "SELECT dirty FROM items WHERE localId = 'i2'"))
            assertEquals(12L, readLong(connection, "SELECT status_updatedAt FROM items WHERE localId = 'i2'"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 9 to 10 keeps the stub lists 8 to 9 gave orphan items`() {
        val connection = openFresh("v9-stub")
        try {
            seedV9(connection, orphans = true)
            val owner = readText(connection, "SELECT id FROM accounts")
            MIGRATION_9_10.migrate(supportFacade(connection))

            assertEquals(2L, count(connection, "SELECT COUNT(*) FROM lists"))
            assertEquals("gone", readText(connection, "SELECT serverId FROM lists WHERE localId = 'gone'"))
            assertEquals(1L, readLong(connection, "SELECT deleted_value FROM lists WHERE localId = 'gone'"))
            assertEquals("gone", readText(connection, "SELECT listLocalId FROM items WHERE localId = 'orphan-1'"))
            assertEquals(owner, readText(connection, "SELECT accountId FROM items WHERE localId = 'orphan-1'"))
            assertEquals("still unpushed", 1L, readLong(connection, "SELECT dirty FROM items WHERE localId = 'orphan-1'"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 9 to 10 gives an item whose list is gone a stub of the first server account`() {
        val connection = openFresh("v9-orphan")
        try {
            seedV9(connection)
            addV9AccountWithList(connection, accountId = "acct-2", listId = "l2", itemId = "i2")
            val first = readText(connection, "SELECT id FROM accounts WHERE id != 'acct-2'")!!
            insertItem(connection, "orphan-9", listId = "gone-9", dirty = true)
            MIGRATION_9_10.migrate(supportFacade(connection))

            assertEquals(first, readText(connection, "SELECT accountId FROM lists WHERE localId = 'gone-9'"))
            assertEquals(1L, readLong(connection, "SELECT deleted_value FROM lists WHERE localId = 'gone-9'"))
            assertEquals(0L, readLong(connection, "SELECT dirty FROM lists WHERE localId = 'gone-9'"))
            assertEquals(first, readText(connection, "SELECT accountId FROM items WHERE localId = 'orphan-9'"))
            assertEquals(3L, count(connection, "SELECT COUNT(*) FROM items"))
            connection.prepare("PRAGMA foreign_key_check").use { assertTrue(!it.step()) }
        } finally {
            connection.close()
        }
    }

    /** A version-9 account row of [kind]; a local one has no server. */
    private fun insertV9Account(connection: SQLiteConnection, id: String, kind: String, sortOrder: Int) {
        val server = if (kind == "server") "'https://$id.example.com/', 'acc-$id'" else "NULL, NULL"
        connection.execSQL(
            "INSERT INTO accounts (id, kind, serverUrl, accountId, email, isAdmin, label, signedIn, outdated, " +
                "serverProtocol, syncCursor, defaultCurrency, ignoredInviteIdsJson, allowSelfSignedCerts, sortOrder) " +
                "VALUES ('$id', '$kind', $server, NULL, 0, '$id', ${if (kind == "server") 1 else 0}, 0, NULL, 0, NULL, " +
                "'[]', 0, $sortOrder)",
        )
    }

    /** A version-9 database with no account and no list, as a fresh install has it. */
    private fun emptyV9(connection: SQLiteConnection) {
        connection.execSQL(v1Lists)
        connection.execSQL(v1Items)
        runMigrations(connection, from = 1, to = 9)
    }

    @Test
    fun `migrating 9 to 10 gives an item whose list is gone a stub of the local account when there is no server account`() {
        val connection = openFresh("v9-local-only")
        try {
            emptyV9(connection)
            insertV9Account(connection, "phone", kind = "local", sortOrder = 0)
            insertItem(connection, "orphan-1", listId = "gone", dirty = true)
            MIGRATION_9_10.migrate(supportFacade(connection))

            assertEquals("phone", readText(connection, "SELECT accountId FROM lists WHERE localId = 'gone'"))
            assertEquals("phone", readText(connection, "SELECT accountId FROM items WHERE localId = 'orphan-1'"))
            connection.prepare("PRAGMA foreign_key_check").use { assertTrue(!it.step()) }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 9 to 10 prefers a server account for the stub over a local one ahead of it`() {
        val connection = openFresh("v9-local-and-server")
        try {
            emptyV9(connection)
            // First in both the user's order and the table: only the kind puts the server first.
            insertV9Account(connection, "phone", kind = "local", sortOrder = 0)
            insertV9Account(connection, "prod", kind = "server", sortOrder = 1)
            insertItem(connection, "orphan-1", listId = "gone", dirty = true)
            MIGRATION_9_10.migrate(supportFacade(connection))

            assertEquals("prod", readText(connection, "SELECT accountId FROM lists WHERE localId = 'gone'"))
            assertEquals("prod", readText(connection, "SELECT accountId FROM items WHERE localId = 'orphan-1'"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 9 to 10 drops an item that neither a list nor any account holds`() {
        val connection = openFresh("v9-nobody")
        try {
            connection.execSQL(v1Lists)
            connection.execSQL(v1Items)
            runMigrations(connection, from = 1, to = 9)
            insertItem(connection, "orphan-1", listId = "gone", dirty = true)
            MIGRATION_9_10.migrate(supportFacade(connection))

            assertEquals(0L, count(connection, "SELECT COUNT(*) FROM lists"))
            assertEquals(0L, count(connection, "SELECT COUNT(*) FROM items"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migrating 1 to 10 lands on the same schema and keeps the rows`() {
        val connection = openFresh("v1-to-10")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = LATEST)

            for (table in listOf("accounts", "lists", "items")) {
                assertEquals(table, expectedColumns(table, LATEST), actualColumns(connection, table))
                assertEquals(table, expectedIndices(table, LATEST), actualIndices(connection, table))
            }
            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists WHERE localId = 'l1'"))
            assertEquals("l1", readText(connection, "SELECT listLocalId FROM items WHERE localId = 'i1'"))
            assertEquals(readText(connection, "SELECT id FROM accounts"), readText(connection, "SELECT accountId FROM items"))
        } finally {
            connection.close()
        }
    }
}
