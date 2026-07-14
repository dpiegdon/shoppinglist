package org.p23q.shoppinglist.data.crash

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [Thread.UncaughtExceptionHandler] (T-50): logs the crash locally via [writer], then
 * always delegates to whatever handler was previously installed - the OS's own crash handling
 * (process death, ANR bookkeeping, Play-console-less crash dialogs) must still run unmodified;
 * this only adds a local record alongside it.
 */
@Singleton
class CrashHandler @Inject constructor(private val writer: CrashLogWriter) : Thread.UncaughtExceptionHandler {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { writer.append(thread.name, throwable) }
        previousHandler?.uncaughtException(thread, throwable)
    }
}
