package org.p23q.shoppinglist.data.crash

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CrashLogModule {
    @Provides
    @Singleton
    fun provideCrashLogWriter(@ApplicationContext context: Context): CrashLogWriter =
        CrashLogWriter(File(context.filesDir, "crash_log.txt"))
}

/**
 * Appends crash stacks to a small rotating log in app-private storage (T-50): no telemetry
 * service, just a local record a self-hoster can pull via Settings > "Share crash logs" when
 * diagnosing a bug. "Rotating" means a bounded ring buffer (oldest content dropped once
 * [maxChars] is exceeded), not a numbered-file history - this is meant to stay small.
 */
class CrashLogWriter(val logFile: File, private val maxChars: Int = DEFAULT_MAX_CHARS) {

    @Synchronized
    fun append(threadName: String, throwable: Throwable, now: Long = System.currentTimeMillis()) {
        val entry = "${TIMESTAMP_FORMAT.format(Date(now))} thread=$threadName\n${throwable.stackTraceToString()}\n"
        val existing = if (logFile.exists()) logFile.readText() else ""
        val combined = existing + entry
        logFile.writeText(if (combined.length > maxChars) combined.takeLast(maxChars) else combined)
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 200_000
        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
