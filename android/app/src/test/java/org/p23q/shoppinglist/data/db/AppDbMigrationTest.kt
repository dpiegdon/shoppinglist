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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

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
 *   until Room implements this** — doing so would break all five migrations at once.
 *
 * So the migration objects are driven directly, against a database built at the old version, and
 * the result is compared against the schema Room itself exported. That comparison is the same one
 * Room performs at runtime: column name, type affinity and nullability, per table. What is not
 * covered is Room's own open-and-validate machinery, which is not this project's code.
 */
class AppDbMigrationTest {

    /**
     * Version 1: the schema before any migration, taken from app/schemas/…/6.json minus every
     * column the five migrations add — so it is Room's own SQL, not a hand-written guess at it.
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

    private val migrations = listOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    )

    /**
     * Hands a migration something that looks like a SupportSQLiteDatabase and forwards the only
     * method migrations use. Anything else fails loudly rather than silently doing nothing, so a
     * future migration that reaches for a query or a transaction cannot pass this test by accident.
     */
    private fun supportFacade(connection: SQLiteConnection): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> connection.execSQL(args[0] as String)
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

    /** What Room says version 6 must look like — its own exported schema, committed alongside. */
    private fun expectedColumns(table: String): Set<Column> {
        val file = File("schemas/org.p23q.shoppinglist.data.db.AppDb/6.json")
        assertTrue("exported schema missing — run the ksp task: ${file.absolutePath}", file.exists())
        val entity = Json.parseToJsonElement(file.readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == table }
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
    fun `migrating 5 to 6 lands on exactly the schema Room expects`() {
        val connection = openFresh("v5")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 5)
            // Only the step under test from here, against the schema 1.13.0 left on real phones.
            MIGRATION_5_6.migrate(supportFacade(connection))

            assertEquals(expectedColumns("lists"), actualColumns(connection, "lists"))
            assertEquals(expectedColumns("items"), actualColumns(connection, "items"))
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
    fun `migrating 1 to 6 lands on the same schema and keeps the rows`() {
        val connection = openFresh("v1")
        try {
            seedV1(connection)
            runMigrations(connection, from = 1, to = 6)

            assertEquals(expectedColumns("lists"), actualColumns(connection, "lists"))
            assertEquals(expectedColumns("items"), actualColumns(connection, "items"))
            assertEquals("Groceries", readText(connection, "SELECT name_value FROM lists"))
            assertEquals("Milk", readText(connection, "SELECT name_value FROM items"))
            // Each intermediate migration's default survived the ones that came after it.
            assertNull(readText(connection, "SELECT notes_value FROM lists"))
            assertEquals("shopping", readText(connection, "SELECT kind_value FROM lists"))
            assertEquals(0L, readLong(connection, "SELECT syncBlocked FROM items"))
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
        val exported = File("schemas/org.p23q.shoppinglist.data.db.AppDb/6.json")
        assertTrue("exported schema missing: ${exported.absolutePath}", exported.exists())
        val declared = Json.parseToJsonElement(exported.readText())
            .jsonObject["database"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt()

        assertEquals(declared, migrations.maxOf(Migration::endVersion))
        // One migration per step, no gaps: a version bump with no migration strands every upgrade.
        assertEquals((2..declared).toList(), migrations.map(Migration::endVersion).sorted())
    }
}
