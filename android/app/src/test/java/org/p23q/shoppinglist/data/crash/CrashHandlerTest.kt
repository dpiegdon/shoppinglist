package org.p23q.shoppinglist.data.crash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CrashHandlerTest {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        // Thread.setDefaultUncaughtExceptionHandler is JVM-global state; every test must restore
        // it or leak a handler into whichever test class runs next.
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun `install replaces the default uncaught exception handler with itself`() {
        val writer = CrashLogWriter(File.createTempFile("crash_handler_test", ".txt").apply { deleteOnExit() })
        val handler = CrashHandler(writer)

        handler.install()

        assertEquals(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `uncaughtException logs the crash then delegates to the previously installed handler`() {
        val file = File.createTempFile("crash_handler_test", ".txt").apply { deleteOnExit() }
        val writer = CrashLogWriter(file)
        var delegatedThread: Thread? = null
        var delegatedThrowable: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { thread, throwable ->
            delegatedThread = thread
            delegatedThrowable = throwable
        }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val handler = CrashHandler(writer)
        handler.install()
        val error = RuntimeException("boom")

        handler.uncaughtException(Thread.currentThread(), error)

        assertTrue(file.readText().contains("boom"))
        assertEquals(Thread.currentThread(), delegatedThread)
        assertEquals(error, delegatedThrowable)
    }

    @Test
    fun `uncaughtException still delegates even if logging itself throws`() {
        val brokenWriter = CrashLogWriter(File("/does-not-exist/unwritable.txt"))
        var delegated = false
        Thread.setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { _, _ -> delegated = true })
        val handler = CrashHandler(brokenWriter)
        handler.install()

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(delegated)
    }

    @Test
    fun `uncaughtException with no previous handler does not throw`() {
        val writer = CrashLogWriter(File.createTempFile("crash_handler_test", ".txt").apply { deleteOnExit() })
        Thread.setDefaultUncaughtExceptionHandler(null)
        val handler = CrashHandler(writer)
        handler.install()

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(writer.logFile.readText().contains("boom"))
    }
}
