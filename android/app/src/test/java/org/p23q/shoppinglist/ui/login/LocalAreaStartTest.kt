package org.p23q.shoppinglist.ui.login

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.FakeLastServerAddress
import org.p23q.shoppinglist.data.LocalArea
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.RecordingAuthRepository
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The start screen's "Use without an account" (T-293): the local area, and its one-time note. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LocalAreaStartTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var registry: AccountRegistry
    private val destinations = mutableListOf<String?>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        registry = AccountRegistry(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun show(mode: LoginMode = LoginMode.START) {
        val viewModel = LoginViewModel(
            RecordingAuthRepository(),
            KnownAccounts { registry.snapshot() },
            FakeLastServerAddress(),
            PendingInviteHolder(),
            FakeSyncTrigger(),
            SavedStateHandle(mapOf(Routes.LOGIN_MODE_ARG to mode.arg)),
            LocalArea { registry.addLocal() != null },
        )
        composeTestRule.setContent { LoginScreen(onLoginSuccess = { destinations += it }, viewModel = viewModel) }
    }

    private val noteBody =
        "Lists here stay on this phone only. They are not backed up and cannot be shared. Sign in to a server any time to add shared lists."

    @Test
    fun `using the app without an account creates the local area, shows the note, then opens the lists`() = runBlocking<Unit> {
        show()

        composeTestRule.onNodeWithTag("login-use-local").performScrollTo().performClick()
        // The note follows the row's write.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodesWithText(noteBody).fetchSemanticsNodes().isNotEmpty()
        }

        val stored = db.accountDao().all().single()
        assertFalse(stored.isServer)
        composeTestRule.onNodeWithText(noteBody).assertExists()
        assertTrue("the lists wait until the note is read", destinations.isEmpty())

        composeTestRule.onNodeWithTag("local-area-note-ok").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(noteBody).assertDoesNotExist()
        assertEquals(listOf<String?>(Routes.OVERVIEW), destinations)
    }

    @Test
    fun `a phone that has the local area already goes straight to its lists, without the note again`() = runBlocking<Unit> {
        val existing = registry.addLocal()!!
        show()

        composeTestRule.onNodeWithTag("login-use-local").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            destinations.isNotEmpty()
        }

        composeTestRule.onNodeWithText(noteBody).assertDoesNotExist()
        assertEquals(listOf<String?>(Routes.OVERVIEW), destinations)
        assertEquals(listOf(existing.id), db.accountDao().all().map { it.id })
    }

    @Test
    fun `only the start screen offers it`() {
        show(LoginMode.ADD)

        composeTestRule.onNodeWithTag("login-use-local").assertDoesNotExist()
    }
}
