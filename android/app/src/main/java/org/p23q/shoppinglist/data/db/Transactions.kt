package org.p23q.shoppinglist.data.db

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] as one database transaction, holding the single writer connection for its duration
 * (T-261).
 *
 * Every read-modify-write in this app — a repo's `updateField`, the sync engine's per-row merge —
 * reads a row, folds one change into it and writes the WHOLE row back. Two of those interleaving
 * lose the earlier one entirely, clocks and all, so the overwritten edit isn't even left dirty and
 * nothing is queued to recover it. They do interleave: the list screen syncs every 5 s while it is
 * open. This is what makes each of them atomic against the others.
 *
 * Deliberately NOT androidx.room's own `withTransaction` extension: that one goes through
 * `RoomDatabase.beginTransaction()`, which exists only on Room's compatibility (SupportSQLite)
 * path and throws "Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory
 * was configured" the moment the database was opened with a SQLiteDriver instead — which is how
 * every test here opens it (BundledSQLiteDriver, the only SQLite that works on Robolectric/aarch64;
 * see app/build.gradle.kts). `useWriterConnection` + `immediateTransaction` is the driver-path API
 * and works on both, so app and tests exercise the same code. It is also what Room's own generated
 * DAO code uses, and Room confines the connection to the calling coroutine, so the plain DAO calls
 * inside [block] — reads included — run on this very connection and inside this transaction.
 *
 * BEGIN IMMEDIATE rather than the default deferred begin: the write lock is taken up front, so a
 * second transaction waits at the door instead of discovering the conflict at its first write and
 * failing with SQLITE_BUSY.
 *
 * [block] must not make network calls or otherwise block: it holds the database's only writer for
 * as long as it runs.
 */
suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
