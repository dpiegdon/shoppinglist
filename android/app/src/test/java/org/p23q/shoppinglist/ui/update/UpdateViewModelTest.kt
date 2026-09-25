package org.p23q.shoppinglist.ui.update

import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.update.AvailableUpdate
import org.p23q.shoppinglist.data.update.UpdateChecker
import org.p23q.shoppinglist.data.update.UpdatePrefsStore
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The "check automatically" switch, which moved here from SettingsViewModel with the block it
 * drives (T-224). The check itself is covered by UpdateCheckerTest.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var prefs: UpdatePrefsStore
    private lateinit var checker: UpdateChecker
    private val allAccounts = mutableListOf<TestAccounts>()

    private fun newAccounts(): TestAccounts = TestAccounts(
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build(),
    ).also { allAccounts += it }

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        server = MockWebServer()
        server.start()

        fun prefsFile(name: String) = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }
        prefs = UpdatePrefsStore(PreferenceDataStoreFactory.create { prefsFile("update_vm_prefs") })
        val accounts = newAccounts()
        accounts.add(server.url("/").toString())
        checker = UpdateChecker(accounts.registry, accounts.sessions, prefs)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        allAccounts.forEach { closeWhenIdle(it.db, pumpMain = {}, registry = it.registry) }
    }

    @Test
    fun `the automatic update check defaults on and the toggle persists it (T-135)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = UpdateViewModel(checker, prefs)
            // Defaults on: a self-hosted app has no store to nag you, so off-by-default would mean
            // never hearing about a release at all.
            assertTrue(viewModel.autoCheckEnabled.first { it })

            viewModel.setAutoCheckEnabled(false).join()

            assertFalse(viewModel.autoCheckEnabled.first { !it })
            assertFalse(prefs.autoCheckEnabled.first())
        }

    @Test
    fun `the blocking screen's check reports through the same status and update (T-244)`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Switched off AND freshly rate-limited: neither may silence the check that is the
            // only way back into an app the server has refused.
            prefs.setAutoCheckEnabled(false)
            prefs.recordCheck(System.currentTimeMillis())
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"version": "99.0.0", "download_url": "https://example.com/shoppinglist.apk"}"""),
            )
            val viewModel = UpdateViewModel(checker, prefs)

            viewModel.checkRequired().join()

            assertEquals(UpdateStatus.Available("99.0.0"), viewModel.status.value)
            assertEquals(
                AvailableUpdate("99.0.0", "https://example.com/shoppinglist.apk"),
                viewModel.availableUpdate.value,
            )
        }

    /** T-304: the outdated banner's and row's check, which the blocking screen may not be there to make. */
    @Test
    fun `an outdated account's check offers a newer app whatever the switch says`() =
        runTest(mainDispatcherRule.dispatcher) {
            prefs.setAutoCheckEnabled(false)
            prefs.recordCheck(System.currentTimeMillis())
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"version": "99.0.0", "download_url": "https://example.com/shoppinglist.apk"}"""),
            )
            val viewModel = UpdateViewModel(checker, prefs)

            viewModel.checkForOutdated().join()

            assertEquals(AvailableUpdate("99.0.0", "https://example.com/shoppinglist.apk"), viewModel.availableUpdate.value)
            assertEquals(null, viewModel.checkNotice.value)
        }

    @Test
    fun `an outdated account's check says so when no server offers a newer app, or none answers`() =
        runTest(mainDispatcherRule.dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"version": "1.0.0", "download_url": "https://example.com/shoppinglist.apk"}"""),
            )
            val viewModel = UpdateViewModel(checker, prefs)

            viewModel.checkForOutdated().join()
            assertTrue(viewModel.checkNotice.value is UpdateStatus.UpToDate)
            assertEquals(null, viewModel.availableUpdate.value)
            viewModel.dismissCheckNotice()
            assertEquals(null, viewModel.checkNotice.value)

            server.shutdown()
            viewModel.checkForOutdated().join()
            assertEquals(UpdateStatus.Failed, viewModel.checkNotice.value)
        }

    @Test
    fun `a check that could not be made is reported, not left looking like it is still running`() =
        runTest(mainDispatcherRule.dispatcher) {
            server.shutdown()
            val viewModel = UpdateViewModel(checker, prefs)

            viewModel.checkRequired().join()

            // Idle reads as "still checking" on the blocking screen, which would be a dead end.
            assertEquals(UpdateStatus.Failed, viewModel.status.value)
        }

    @Test
    fun `a check with nothing to ask is reported the same way, not as still checking`() =
        runTest(mainDispatcherRule.dispatcher) {
            // No server configured is the one thing that still stops a forced check. The About
            // screen renders that outcome as silence; the blocking screen must not, or it would
            // sit on its spinner with no way out.
            val none = newAccounts()
            val unconfigured = UpdateChecker(none.registry, none.sessions, prefs)
            val viewModel = UpdateViewModel(unconfigured, prefs)

            viewModel.checkRequired().join()

            assertEquals(UpdateStatus.Failed, viewModel.status.value)
        }
}
