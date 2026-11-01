package org.p23q.shoppinglist.data.crash

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.experimental.ExperimentalNativeApi

/**
 * The crash log Settings > "Share crash logs" hands to the share sheet, on iOS (T-50): the stack
 * of every uncaught Kotlin exception, appended by [install]'s hook before the process ends. What
 * ends the process outside Kotlin (a signal, the system) leaves no line here.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, BetaInteropApi::class)
class IosCrashLog(private val path: String) : CrashLog {

    override fun shareablePath(): String? {
        val size = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? Long
        return if (size != null && size > 0) path else null
    }

    /** Appends [throwable]'s stack trace, dated; a failure to write is swallowed, as the process is ending. */
    fun append(throwable: Throwable) {
        val existing = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
        val entry = "--- ${NSDate()} ---\n${throwable.stackTraceToString()}\n"
        NSString.create(string = existing + entry).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    /**
     * Logs every uncaught exception here, then lets the previous hook (Kotlin's own, which
     * terminates the process) run, as Android's `CrashHandler` chains to the system's.
     */
    fun install() {
        var previous: ReportUnhandledExceptionHook? = null
        val hook: ReportUnhandledExceptionHook = { throwable ->
            runCatching { append(throwable) }
            previous?.invoke(throwable)
        }
        previous = setUnhandledExceptionHook(hook)
    }
}
