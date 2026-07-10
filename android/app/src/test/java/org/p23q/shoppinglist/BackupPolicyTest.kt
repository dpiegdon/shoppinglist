package org.p23q.shoppinglist

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the manifest policy from T-37: allowBackup must stay off, or a cloud/backup restore would
 * carry SessionStore's encrypted prefs to a new device whose Keystore master key can't decrypt them,
 * crash-looping the app at startup.
 */
@RunWith(RobolectricTestRunner::class)
class BackupPolicyTest {

    @Test
    fun `allowBackup is disabled`() {
        val appInfo = ApplicationProvider.getApplicationContext<Context>().applicationInfo

        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
