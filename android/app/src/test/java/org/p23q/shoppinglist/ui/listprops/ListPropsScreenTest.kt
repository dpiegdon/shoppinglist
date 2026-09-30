package org.p23q.shoppinglist.ui.listprops

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import org.p23q.shoppinglist.ui.InBrandColors
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.assertDangerSectionCard
import org.p23q.shoppinglist.ui.assertInSectionCard
import org.p23q.shoppinglist.ui.assertPlainSectionCards
import org.p23q.shoppinglist.ui.assertSectionCardOrder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ListPropsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    private val me = "acct-me"

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `renders the name and category order, and unsubscribe opens a confirm dialog`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = server.url("/").toString())) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        listsRepo.setCategoryOrder(listId, listOf("dairy"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }

        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var left = false

        composeTestRule.setContent { ListPropsScreen(onLeft = { left = true }, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Groceries").assertExists()
        composeTestRule.onNodeWithText("dairy").assertExists()

        composeTestRule.onNode(hasText("Leave list") and hasClickAction()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Leave this list?").assertExists()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
        assertEquals(false, left)
    }

    /**
     * T-30, kept by T-307's shared drag-reorder: a category moves one place per row it is dragged
     * over. The step is the rows' measured height (T-340): a fixed 44dp step, shorter than the
     * rows, swapped a row too many here and let the dragged row drift from the finger.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `dragging a category's handle moves it one place per row height`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount())
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery", "produce", "frozen"))
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_drag_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.categoryOrder.size == 4 }
        composeTestRule.waitForIdle()

        // The rows' own pitch, as drawn: 2.5 of them, less the touch slop, is two swaps.
        val pitch = composeTestRule.onNodeWithContentDescription("Reorder bakery").fetchSemanticsNode().boundsInRoot.top -
            composeTestRule.onNodeWithContentDescription("Reorder dairy").fetchSemanticsNode().boundsInRoot.top
        assertTrue("rows are taller than 44dp", pitch > with(composeTestRule.density) { 48.dp.toPx() })
        val distance = pitch * 2.5f
        composeTestRule.onNodeWithContentDescription("Reorder dairy").performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(0f, distance / 40)) }
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("bakery", "produce", "dairy", "frozen"), viewModel.uiState.value.categoryOrder)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    /**
     * T-340: the handle is a 48dp target that offers Move up and Move down to TalkBack, as the
     * accounts' handles do; Save order is offered only once the order changed, and then says Saved.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a category handle offers Move up and Move down, and Save order says Saved`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount())
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery", "produce"))
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_moves_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.categoryOrder.size == 3 }
        composeTestRule.waitForIdle()

        fun actions(category: String) = composeTestRule.onNodeWithContentDescription("Reorder $category")
            .fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

        composeTestRule.onNodeWithContentDescription("Reorder dairy").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertEquals(listOf("Move down"), actions("dairy").map { it.label })
        assertEquals(listOf("Move up", "Move down"), actions("bakery").map { it.label })
        assertEquals(listOf("Move up"), actions("produce").map { it.label })
        composeTestRule.onNodeWithText("Save order").assertIsNotEnabled()

        composeTestRule.runOnUiThread { actions("dairy").single { it.label == "Move down" }.action() }
        composeTestRule.waitForIdle()
        assertEquals(listOf("bakery", "dairy", "produce"), viewModel.uiState.value.categoryOrder)

        composeTestRule.onNodeWithText("Save order").assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.orderSaved }
        composeTestRule.assertInSectionCard("Categories", "Saved.")
        composeTestRule.onNodeWithText("Save order").assertIsNotEnabled()
        assertEquals(
            listOf("bakery", "dairy", "produce"),
            listsRepo.decodeCategoryOrder(listsRepo.getById(listId)!!.categoryOrder.value),
        )
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `typing a note and saving persists it as an LWW edit`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = server.url("/").toString())) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")

        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )

        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Notes").assertExists()
        // Scroll the field into view first (T-110 added a Type section above it, pushing Notes off
        // the viewport) — same convention the leave/duplicate tests in this file already use.
        composeTestRule.onNodeWithText("Gate code, store hours, anything worth remembering…")
            .performScrollTo()
            .performTextInput("Gate code: 4471")
        composeTestRule.onNodeWithText("Save notes").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("Gate code: 4471", listsRepo.getById(listId)!!.notes.value)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `tapping Duplicate creates a copy and navigates to it (T-63)`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = server.url("/").toString())) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Milk")

        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var duplicatedListId: String? = null

        composeTestRule.setContent {
            ListPropsScreen(onLeft = {}, onDuplicated = { duplicatedListId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Duplicate list").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, duplicatedListId != null && duplicatedListId != listId)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    /** T-302: the name is synced data, so it takes the copier's language; "(Copy)" was English on every phone. */
    @Test
    @Config(qualifiers = "de")
    fun `a copy's name ends in the app's word for copy`() = runBlocking<Unit> {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount(serverUrl = server.url("/").toString()))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")

        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var duplicatedListId: String? = null
        composeTestRule.setContent {
            ListPropsScreen(onLeft = {}, onDuplicated = { duplicatedListId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Liste duplizieren").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { duplicatedListId != null }

        assertEquals("Groceries (Kopie)", listsRepo.getById(duplicatedListId!!)!!.name.value)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `with several accounts, Duplicate asks where to, naming each account, and copies there (T-294)`() = runBlocking<Unit> {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount(serverUrl = server.url("/").toString()))
        db.insertTestAccount(testAccount(id = "work", serverUrl = "https://work.example.test/", accountId = "acct-work", email = "me@work.example"))
        db.accountDao().insert(org.p23q.shoppinglist.ui.accounts.localAccountRow("on-phone"))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")

        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var duplicatedListId: String? = null
        composeTestRule.setContent {
            ListPropsScreen(onLeft = {}, onDuplicated = { duplicatedListId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Duplicate list").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Copy to").assertIsDisplayed()
        // The one "which account" row (A7): the account, then its server on a second line.
        composeTestRule.onNodeWithTag(COPY_TARGET_TAG_PREFIX + TEST_ACCOUNT_ID).assertTextEquals("me@example.com", server.url("/").toString())
        composeTestRule.onNodeWithTag(COPY_TARGET_TAG_PREFIX + "work").assertTextEquals("me@work.example", "https://work.example.test/")
        composeTestRule.onNodeWithTag(COPY_TARGET_TAG_PREFIX + "on-phone").assertTextEquals("On this phone")
        composeTestRule.onNodeWithTag(COPY_TARGET_TAG_PREFIX + "work").assertHeightIsAtLeast(48.dp)
        assertEquals("nothing copied before a pick", null, duplicatedListId)

        composeTestRule.onNodeWithText("On this phone").performClick()
        composeTestRule.waitForIdle()

        assertEquals("on-phone", listsRepo.getById(duplicatedListId!!)!!.accountId)
        composeTestRule.onNodeWithText("Copy to").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    /**
     * An expenses list's settings, shown to [me] with [closeVotes] already cast. The roster and
     * votes normally arrive from the server on the list row.
     */
    private suspend fun showExpenseSettings(closeVotes: List<String>): Pair<AppDb, ListPropsViewModel> {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = server.url("/").toString())) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip", ListKind.EXPENSES, currency = "EUR")
        db.listDao().upsert(listsRepo.getById(listId)!!.copy(closeVotesJson = Json.encodeToString(closeVotes)))

        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )

        composeTestRule.setContent { InBrandColors { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) } }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.local != null }
        composeTestRule.waitForIdle()
        return db to viewModel
    }
    @Test
    fun `once I have agreed to close, the name and notes are locked and it says why (T-193)`() = runBlocking {
        val (db, viewModel) = showExpenseSettings(closeVotes = listOf(me))

        composeTestRule.onNodeWithText("You've agreed to close this list", substring = true).assertExists()
        composeTestRule.onNodeWithText("Trip").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Gate code, store hours, anything worth remembering…")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save notes").performScrollTo().assertIsNotEnabled()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `someone else's vote leaves my name and notes editable`() = runBlocking {
        val (db, viewModel) = showExpenseSettings(closeVotes = listOf("acct-other"))

        composeTestRule.onNodeWithText("You've agreed to close this list", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Trip").assertIsEnabled()
        composeTestRule.onNodeWithText("Gate code, store hours, anything worth remembering…")
            .performScrollTo()
            .assertIsEnabled()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a list in the local area shows only you, no invites or notifications, and offers Delete (T-293)`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.accountDao().insert(org.p23q.shoppinglist.ui.accounts.localAccountRow("on-phone"))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create("on-phone", "Hardware")
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var left = false

        composeTestRule.setContent { ListPropsScreen(onLeft = { left = true }, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.local != null }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("You").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Lists on this phone cannot be shared.").assertExists()
        composeTestRule.onNodeWithText("Invite by email").assertDoesNotExist()
        composeTestRule.onNodeWithText("Notify about changes to this list").assertDoesNotExist()
        composeTestRule.onNodeWithText("Leave list").assertDoesNotExist()

        composeTestRule.onNode(hasText("Delete list") and hasClickAction()).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Delete this list?").assertExists()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { left }

        assertEquals(null, listsRepo.getById(listId))
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a shopping list's sections sit in cards in the order both clients share, Clear checked last in the List card (T-337)`() = runBlocking<Unit> {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount(serverUrl = server.url("/").toString()))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Milk", Status.CHECKED)
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )

        composeTestRule.setContent { InBrandColors { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) } }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.local != null && viewModel.uiState.value.checkedCount == 1
        }
        composeTestRule.waitForIdle()

        composeTestRule.assertSectionCardOrder(
            "List", "Categories", "Notes", "Shared with", "Notifications", "Actions", "Leave list",
        )
        composeTestRule.assertPlainSectionCards("List", "Categories", "Notes", "Shared with", "Notifications", "Actions")
        composeTestRule.assertDangerSectionCard("Leave list")
        composeTestRule.assertInSectionCard("List", "Clear checked (1)")
        composeTestRule.assertInSectionCard("List", "Move every checked item back to the backlog.")
        composeTestRule.assertInSectionCard("List", "Type")
        composeTestRule.assertInSectionCard("Actions", "Duplicate list")
        composeTestRule.onNodeWithText("Closing").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a ledger's sections sit in cards with Closing directly above Leave, and no Actions (T-337)`() = runBlocking<Unit> {
        val (db, viewModel) = showExpenseSettings(closeVotes = emptyList())

        composeTestRule.assertSectionCardOrder("List", "Notes", "Shared with", "Notifications", "Closing", "Leave list")
        composeTestRule.assertPlainSectionCards("List", "Notes", "Shared with", "Notifications", "Closing")
        composeTestRule.assertDangerSectionCard("Leave list")
        composeTestRule.assertInSectionCard("Leave list", "A ledger can only be left once it is closed.")
        // A failure shows in the card whose action failed (T-340), not between cards.
        viewModel.sendInvite()
        composeTestRule.waitForIdle()
        composeTestRule.assertInSectionCard("Shared with", "Enter an email address")
        composeTestRule.onNodeWithText("Categories").assertDoesNotExist()
        composeTestRule.onNodeWithText("Actions").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a list in the local area keeps the same cards, its Shared with saying it cannot be, and Delete in the danger card (T-337)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.accountDao().insert(org.p23q.shoppinglist.ui.accounts.localAccountRow("on-phone"))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create("on-phone", "Hardware")
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            testListAccounts(db, listsRepo),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )

        composeTestRule.setContent { InBrandColors { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) } }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.local != null }
        composeTestRule.waitForIdle()

        composeTestRule.assertSectionCardOrder("List", "Categories", "Notes", "Shared with", "Actions", "Delete list")
        composeTestRule.assertInSectionCard("Shared with", "Lists on this phone cannot be shared.")
        composeTestRule.assertDangerSectionCard("Delete list")
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }
}
