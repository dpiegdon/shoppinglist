package org.p23q.shoppinglist.data

import androidx.datastore.core.DataStore
import kotlinx.cinterop.ExperimentalForeignApi
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Where the iOS app keeps its files: the database, each preference store's file and the crash
 * log, all under one directory. The app's is in its Application Support directory (iCloud leaves
 * it out of backups only when asked; see T-354 for what still has to be decided); a test uses a
 * temporary one.
 */
@OptIn(ExperimentalForeignApi::class)
class AppFiles(val directory: String) {
    init {
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    }

    val databasePath: String get() = "$directory/shoppinglist.db"

    val crashLogPath: String get() = "$directory/crash.log"

    /** One DataStore file per store, created once by the one store that owns it, as on Android. */
    fun preferencesDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath { "$directory/$name.preferences_pb".toPath() }

    companion object {
        /** The app's own directory: `Application Support/Tuppu` in the app's sandbox. */
        fun inApplicationSupport(): AppFiles {
            val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: error("no Application Support directory")
            return AppFiles("$support/Tuppu")
        }
    }
}
