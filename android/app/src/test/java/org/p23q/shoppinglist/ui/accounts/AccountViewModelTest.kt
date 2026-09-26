package org.p23q.shoppinglist.ui.accounts

import org.p23q.shoppinglist.data.runCurrentOn
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.LocalAreaNotEmptyException
import org.p23q.shoppinglist.data.RecordingAuthRepository
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.testListsRepo
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** The account half of what was Settings, now per account (T-292). */
@RunWith(RobolectricTestRunner::class)
class AccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private val authRepository = RecordingAuthRepository()
    private val viewModels = mutableListOf<AccountViewModel>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        accounts = TestAccounts(db)
        runBlocking {
            accounts.add(server.url("/").toString(), email = "milk@example.com")
            accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(defaultCurrency = "EUR") }
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, runCurrentOn(mainDispatcherRule.dispatcher), viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
        if (::server.isInitialized) server.shutdown()
    }

    private fun newViewModel(id: String = TEST_ACCOUNT_ID, auth: AuthRepository = authRepository): AccountViewModel =
        AccountViewModel(
            SavedStateHandle(mapOf(Routes.ACCOUNT_ID_ARG to id)),
            accounts.registry,
            accounts.sessions,
            auth,
            db,
        ).also { viewModels += it }

    /** A second account on the same server, under another path: stage beside prod. */
    private suspend fun addStage() =
        accounts.add(server.url("/stage/").toString(), id = "stage", token = "tok-stage", accountId = "acct-stage", email = "stage@example.com")

    @Test
    fun `the screen shows the account it was opened for, with no network call`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()

        val state = newViewModel("stage").uiState.value

        assertEquals(server.url("/stage/").toString(), state.account?.serverUrl)
        assertEquals("stage@example.com", state.account?.email)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `requests go to the account's own server with its own token`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sessions": []}"""))

        newViewModel("stage").loadSessions().join()

        val request = server.takeRequest()
        assertTrue(request.path!!, request.path!!.startsWith("/stage/"))
        assertEquals("Bearer tok-stage", request.getHeader("Authorization"))
    }

    @Test
    fun `the initial currency is the account's cached one`() = runTest(mainDispatcherRule.dispatcher) {
        assertEquals("EUR", newViewModel().uiState.value.defaultCurrency)
    }

    @Test
    fun `updateCurrency rejects an invalid code locally without calling the server`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        val job = viewModel.updateCurrency("euros")

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `updateCurrency stores the currency on this account and no other`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "MI"}"""))
        val viewModel = newViewModel()

        viewModel.updateCurrency("usd")?.join()

        assertEquals("USD", viewModel.uiState.value.defaultCurrency)
        // An open list of this account follows the row (the list screen reads it, T-55).
        assertEquals("USD", accounts.registry.get(TEST_ACCOUNT_ID)!!.defaultCurrency)
        assertNull(accounts.registry.get("stage")!!.defaultCurrency)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `updateCurrency never sends initials, and leaves the loaded value on screen (T-103)`() = runTest(mainDispatcherRule.dispatcher) {
        // GET and PATCH share the same /settings path; route by method rather than by FIFO order.
        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return when (request.method) {
                    "PATCH" -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "XY"}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "XY"}""")
                }
            }
        }
        val viewModel = newViewModel()
        viewModel.loadInitials().join()

        viewModel.updateCurrency("usd")?.join()

        // Absent, not echoed back: resending the resolved value pinned an email-derived default
        // as a real override (T-103).
        val patchRequest = recordedRequests.first { it.method == "PATCH" }
        assertFalse(patchRequest.body.readUtf8().contains("initials"))
        assertEquals("XY", viewModel.uiState.value.initials)
    }

    @Test
    fun `updateCurrency omits the initials key when the preload has not resolved (T-97)`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": ""}"""))
        val viewModel = newViewModel()

        viewModel.updateCurrency("usd")?.join()

        assertFalse(server.takeRequest().body.readUtf8().contains("initials"))
    }

    @Test
    fun `an explicit initials save sends it, and a later currency save still does not (T-103)`() = runTest(mainDispatcherRule.dispatcher) {
        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "ZZ"}""")
            }
        }
        val viewModel = newViewModel()

        viewModel.updateInitials("zz")?.join()
        viewModel.updateCurrency("usd")?.join()

        val patchRequests = recordedRequests.filter { it.method == "PATCH" }
        assertEquals(2, patchRequests.size)
        assertTrue(patchRequests[0].body.readUtf8().contains("\"initials\":\"ZZ\""))
        assertFalse(patchRequests[1].body.readUtf8().contains("initials"))
    }

    @Test
    fun `updateInitials rejects more than 3 characters locally without calling the server`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        assertNull(viewModel.updateInitials("TooLong"))
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    /** T-300: a failed read at sign-in left the currency empty; the settings load fills it in. */
    @Test
    fun `the settings load stores the currency on the row`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(defaultCurrency = null) }
        val viewModel = newViewModel()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "SEK", "initials": "MI"}"""))

        viewModel.loadInitials().join()

        assertEquals("SEK", accounts.registry.get(TEST_ACCOUNT_ID)!!.defaultCurrency)
        assertEquals("SEK", viewModel.uiState.value.defaultCurrency)
    }

    @Test
    fun `updateInitials sends only the initials, and takes the currency the server answers (T-316)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            // Another device changed the currency since this screen read EUR.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "SEK", "initials": "AB"}"""))

            viewModel.updateInitials("ab")?.join()

            assertEquals("""{"initials":"AB"}""", server.takeRequest().body.readUtf8())
            assertEquals("AB", viewModel.uiState.value.initials)
            assertEquals("SEK", viewModel.uiState.value.defaultCurrency)
            assertEquals("SEK", accounts.registry.get(TEST_ACCOUNT_ID)!!.defaultCurrency)
        }

    @Test
    fun `changePassword with the wrong current password surfaces an inline error`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "unauthorized", "message": "wrong password"}"""))
        val viewModel = newViewModel()
        viewModel.onCurrentPasswordChange("wrong")
        viewModel.onNewPasswordChange("newpass123")
        viewModel.onNewPasswordAgainChange("newpass123")

        viewModel.changePassword()?.join()

        assertEquals(UiText.res(R.string.settings_msg_password_incorrect), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `changePassword success clears the fields and shows a confirmation`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onCurrentPasswordChange("hunter2")
        viewModel.onNewPasswordChange("newpass123")
        viewModel.onNewPasswordAgainChange("newpass123")

        viewModel.changePassword()?.join()

        assertEquals("", viewModel.uiState.value.currentPassword)
        assertEquals("", viewModel.uiState.value.newPassword)
        assertEquals("", viewModel.uiState.value.newPasswordAgain)
        assertNull(viewModel.uiState.value.errorMessage)
        assertNotNull(viewModel.uiState.value.infoMessage)
    }

    @Test
    fun `changePassword with two different new passwords sends nothing and flags the repeat field (T-313)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.onCurrentPasswordChange("hunter2")
        viewModel.onNewPasswordChange("newpass123")
        viewModel.onNewPasswordAgainChange("newpass124")
        val before = server.requestCount

        assertNull(viewModel.changePassword())
        runCurrent()

        assertEquals(before, server.requestCount)
        assertTrue(viewModel.uiState.value.newPasswordMismatch)
        // Typing in either field takes the complaint away.
        viewModel.onNewPasswordAgainChange("newpass12")
        assertFalse(viewModel.uiState.value.newPasswordMismatch)
        assertNull(viewModel.changePassword())
        assertTrue(viewModel.uiState.value.newPasswordMismatch)
        viewModel.onNewPasswordChange("newpass12")
        assertFalse(viewModel.uiState.value.newPasswordMismatch)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `changePassword with matching new passwords sends the one new password as before (T-313)`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onCurrentPasswordChange("hunter2")
        viewModel.onNewPasswordChange("newpass123")
        viewModel.onNewPasswordAgainChange("newpass123")

        viewModel.changePassword()!!.join()

        val request = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .first { it.path?.endsWith("/account/change-password") == true }
        // The repeat never leaves the phone: the body is exactly what it was before.
        assertEquals(
            mapOf("current_password" to "hunter2", "new_password" to "newpass123"),
            kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                .mapValues { it.value.jsonPrimitive.content },
        )
        assertFalse(viewModel.uiState.value.newPasswordMismatch)
    }

    @Test
    fun `changeEmail success stores the new email on the account`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onChangeEmailPasswordChange("hunter2")
        viewModel.onNewEmailChange("new@example.com")

        viewModel.changeEmail()?.join()

        assertEquals("new@example.com", accounts.registry.get(TEST_ACCOUNT_ID)!!.email)
        assertEquals("new@example.com", viewModel.uiState.first { it.account?.email == "new@example.com" }.account?.email)
    }

    @Test
    fun `loadSessions tolerates a null device_label`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions": [{"id": "s1", "device_label": null, "created_at": 1, "last_seen_at": 2, "current": true}]}""",
            ),
        )
        val viewModel = newViewModel()

        viewModel.loadSessions().join()

        assertNull(viewModel.uiState.value.sessions.single().deviceLabel)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `revokeSession calls the server then refreshes the list`() = runTest(mainDispatcherRule.dispatcher) {
        val two = """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 1, "last_seen_at": 2, "current": true},""" +
            """{"id": "s2", "device_label": "Other", "created_at": 1, "last_seen_at": 2, "current": false}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(two))
        val viewModel = newViewModel()
        viewModel.loadSessions().join()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 1, "last_seen_at": 2, "current": true}]}""",
            ),
        )

        viewModel.revokeSession("s2").join()

        assertEquals(listOf("s1"), viewModel.uiState.value.sessions.map { it.id })
    }

    @Test
    fun `the self-signed opt-in is this account's alone`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        val viewModel = newViewModel()

        viewModel.setAllowSelfSignedCerts(true).join()

        assertTrue(accounts.registry.get(TEST_ACCOUNT_ID)!!.allowSelfSignedCerts)
        assertFalse(accounts.registry.get("stage")!!.allowSelfSignedCerts)
    }

    @Test
    fun `confirmDeleteAccount with the wrong password surfaces an inline error and removes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "unauthorized", "message": "wrong password"}"""))
        val viewModel = newViewModel()
        viewModel.onDeleteAccountPasswordChange("wrong")

        viewModel.confirmDeleteAccount()?.join()

        assertEquals(UiText.res(R.string.settings_msg_delete_password_incorrect), viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.gone)
        assertTrue(authRepository.removed.isEmpty())
    }

    @Test
    fun `deleting the only account on its server removes it here and goes to the start screen`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onDeleteAccountPasswordChange("hunter2")

        viewModel.confirmDeleteAccount()?.join()

        assertEquals(listOf(TEST_ACCOUNT_ID), authRepository.removed)
        assertEquals(AccountGone.TO_START, viewModel.uiState.value.gone)
    }

    @Test
    fun `deleting one of two accounts goes back to the Accounts screen`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel("stage")
        viewModel.onDeleteAccountPasswordChange("hunter2")

        viewModel.confirmDeleteAccount()?.join()

        assertEquals(listOf("stage"), authRepository.removed)
        assertEquals(AccountGone.TO_ACCOUNTS, viewModel.uiState.value.gone)
    }

    @Test
    fun `the removal warning counts this account's unpushed and quarantined rows, and nobody else's`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        val lists = testListsRepo(db)
        val items = org.p23q.shoppinglist.core.repo.ItemsRepo(db, { "this-device" }, org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        val mine = lists.create(TEST_ACCOUNT_ID, "Groceries")
        items.createItem(mine, "Milk")
        val blocked = items.createItem(mine, "Bread")
        db.itemDao().blockRow(blocked, "invalid", null)
        lists.create("stage", "Not mine")
        val viewModel = newViewModel()

        viewModel.requestRemove().join()

        assertTrue(viewModel.uiState.value.isRemoveConfirmOpen)
        // The list, the pending item and the quarantined one; stage's list is not this account's.
        assertEquals(3, viewModel.uiState.value.unpushedCount)
        assertTrue(authRepository.removed.isEmpty())
    }

    @Test
    fun `removing from this phone removes the account without asking its server`() = runTest(mainDispatcherRule.dispatcher) {
        addStage()
        val viewModel = newViewModel()
        viewModel.requestRemove().join()

        viewModel.confirmRemove().join()

        assertEquals(listOf(TEST_ACCOUNT_ID), authRepository.removed)
        assertEquals(AccountGone.TO_ACCOUNTS, viewModel.uiState.value.gone)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling the removal removes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.requestRemove().join()

        viewModel.cancelRemove()

        assertFalse(viewModel.uiState.value.isRemoveConfirmOpen)
        assertTrue(authRepository.removed.isEmpty())
    }

    @Test
    fun `the local area is removed only once it holds no lists, and without a warning then (T-293)`() = runTest(mainDispatcherRule.dispatcher) {
        val local = accounts.registry.addLocal()!!
        val lists = testListsRepo(db)
        val listId = lists.create(local.id, "Hardware")
        val viewModel = newViewModel(local.id)
        assertEquals(1, viewModel.uiState.first { it.listCount != null }.listCount)

        viewModel.requestRemove().join()
        viewModel.confirmRemove().join()

        assertTrue("a list would be gone for good", authRepository.removed.isEmpty())
        assertFalse(viewModel.uiState.value.isRemoveConfirmOpen)

        // Its lists go one by one, as any list does.
        lists.removeLocally(listId)
        viewModel.uiState.first { it.listCount == 0 }
        viewModel.requestRemove().join()

        assertEquals(listOf(local.id), authRepository.removed)
        assertFalse(viewModel.uiState.value.isRemoveConfirmOpen)
        assertEquals(AccountGone.TO_ACCOUNTS, viewModel.uiState.value.gone)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `removing the last server account leaves the local area, so it goes to Accounts, not the start (T-293)`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.registry.addLocal()
        val viewModel = newViewModel()

        viewModel.requestRemove().join()
        viewModel.confirmRemove().join()

        assertEquals(listOf(TEST_ACCOUNT_ID), authRepository.removed)
        assertEquals(AccountGone.TO_ACCOUNTS, viewModel.uiState.value.gone)
    }

    @Test
    fun `removing the local area when it is the only account goes to the start screen (T-293)`() = runTest(mainDispatcherRule.dispatcher) {
        accounts.registry.remove(TEST_ACCOUNT_ID)
        val local = accounts.registry.addLocal()!!
        val viewModel = newViewModel(local.id)
        viewModel.uiState.first { it.listCount == 0 }

        viewModel.requestRemove().join()

        assertEquals(listOf(local.id), authRepository.removed)
        assertEquals(AccountGone.TO_START, viewModel.uiState.value.gone)
    }

    /** T-302: the registry counts the lists again; a list this screen had not seen yet keeps the area. */
    @Test
    fun `a refused removal of the local area stays on its screen and says why`() = runTest(mainDispatcherRule.dispatcher) {
        val local = accounts.registry.addLocal()!!
        val refusing = object : RecordingAuthRepository() {
            override suspend fun removeAccount(accountId: String) {
                throw LocalAreaNotEmptyException()
            }
        }
        val viewModel = newViewModel(local.id, auth = refusing)
        viewModel.uiState.first { it.listCount == 0 }
        assertFalse(viewModel.uiState.value.removeBlocked)

        viewModel.requestRemove().join()

        assertTrue(viewModel.uiState.value.removeBlocked)
        assertNull(viewModel.uiState.value.gone)
        assertFalse(viewModel.uiState.value.isRemoveConfirmOpen)
    }
}
