package org.p23q.shoppinglist.core.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The database an iOS app opens, at [path] (a file in its Application Support or Documents
 * directory), on the bundled SQLite driver. It starts at the current schema; the migrations from
 * older ones are Android's alone, since no iPhone holds such a database.
 */
fun buildAppDb(path: String): AppDb = Room.databaseBuilder<AppDb>(name = path)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()
