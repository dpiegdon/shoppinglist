package org.p23q.shoppinglist.data.crash

/**
 * The local crash log Settings > "Share crash logs" hands to the share sheet (T-50). The platform
 * writes it: on Android [CrashLogWriter], from the app's uncaught-exception handler.
 */
interface CrashLog {
    /** The log's absolute path when it has something in it; null when it is missing or empty. */
    fun shareablePath(): String?
}
