package org.p23q.shoppinglist.data.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashLogWriterTest {

    @Test
    fun `append writes a timestamped entry with the thread name and full stack trace`() {
        val file = File.createTempFile("crash_log_test", ".txt").apply { deleteOnExit() }
        val writer = CrashLogWriter(file)
        val error = RuntimeException("boom")

        writer.append("main", error, now = 0L)

        val contents = file.readText()
        assertTrue(contents.contains("thread=main"))
        assertTrue(contents.contains("RuntimeException"))
        assertTrue(contents.contains("boom"))
    }

    @Test
    fun `append accumulates multiple crashes across calls`() {
        val file = File.createTempFile("crash_log_test", ".txt").apply { deleteOnExit() }
        val writer = CrashLogWriter(file)

        writer.append("main", RuntimeException("first"), now = 0L)
        writer.append("worker", IllegalStateException("second"), now = 1L)

        val contents = file.readText()
        assertTrue(contents.contains("thread=main"))
        assertTrue(contents.contains("first"))
        assertTrue(contents.contains("thread=worker"))
        assertTrue(contents.contains("second"))
    }

    @Test
    fun `append rotates out the oldest content once the file exceeds maxChars`() {
        // A real stack trace is thousands of chars, so a small fixed maxChars (e.g. 50) would cut
        // into the middle of even a single entry rather than dropping a whole one. Measure one
        // real entry's length first, then size maxChars to fit exactly one - the only way to
        // deterministically test "oldest entry dropped" without a fragile guessed constant.
        val probeFile = File.createTempFile("crash_log_test_probe", ".txt").apply { deleteOnExit() }
        CrashLogWriter(probeFile).append("main", RuntimeException("second-crash-marker"), now = 1L)
        val oneEntryLength = probeFile.readText().length

        val file = File.createTempFile("crash_log_test", ".txt").apply { deleteOnExit() }
        val writer = CrashLogWriter(file, maxChars = oneEntryLength)

        writer.append("main", RuntimeException("first-crash-marker"), now = 0L)
        writer.append("main", RuntimeException("second-crash-marker"), now = 1L)

        val contents = file.readText()
        assertTrue(contents.length <= oneEntryLength)
        assertTrue(contents.contains("second-crash-marker"))
        assertTrue(!contents.contains("first-crash-marker"))
    }

    @Test
    fun `logFile exposes the file it was constructed with`() {
        val file = File.createTempFile("crash_log_test", ".txt").apply { deleteOnExit() }
        val writer = CrashLogWriter(file)

        assertEquals(file, writer.logFile)
    }
}
