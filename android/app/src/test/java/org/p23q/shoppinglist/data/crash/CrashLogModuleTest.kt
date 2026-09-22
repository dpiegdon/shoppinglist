package org.p23q.shoppinglist.data.crash

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * file_paths.xml must grant exactly the crash log's own subdirectory, not the whole of filesDir
 * (T-265) — filesDir also holds SessionStore's DataStore files. Exercised through FileProvider
 * itself (not by reading the XML), so a drift between the two would fail here whichever one moved.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogModuleTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val authority = "${context.packageName}.fileprovider"

    /**
     * FileProvider caches the PathStrategy it parses from file_paths.xml in a process-wide static
     * map, keyed only by authority — Robolectric does not give every @Test method in a class its
     * own classloader, so without this, whichever test method runs first "wins" the cache for the
     * rest and the other tests below stop actually exercising file_paths.xml at all.
     */
    @Before
    fun resetFileProviderPathCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun `the provided log file lives in its own subdirectory of filesDir, not filesDir itself`() {
        val writer = CrashLogModule.provideCrashLogWriter(context)

        val parent = writer.logFile.parentFile!!
        assertEquals("crash_logs", parent.name)
        assertEquals(context.filesDir.canonicalFile, parent.parentFile!!.canonicalFile)
        assertTrue("the module must create the directory it points at", parent.isDirectory)
    }

    @Test
    fun `FileProvider can share the crash log`() {
        val writer = CrashLogModule.provideCrashLogWriter(context)
        writer.append("main", RuntimeException("boom"), now = 0L)

        // Must not throw: file_paths.xml has to grant this exact file.
        FileProvider.getUriForFile(context, authority, writer.logFile)
    }

    @Test
    fun `FileProvider refuses a file outside the crash_logs directory, unlike a root of a dot`() {
        // A stand-in for anything else directly in filesDir (e.g. a DataStore file) — the T-265
        // finding was that a files-path rooted at "." would have granted this too.
        val other = File(context.filesDir, "not_a_crash_log.txt").apply { writeText("secret") }

        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, other)
        }
    }
}
