package org.p23q.shoppinglist.ui.listprops

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.ListAccounts
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Note: ListPropsViewModel does NOT fetch members/invites in init - only loadMembers() (called
 * explicitly by the screen, mirroring SettingsViewModel.loadSessions()) hits the network, so most
 * tests below never need to enqueue a members response at all.
 */
@RunWith(RobolectricTestRunner::class)
class ListPropsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var listAccounts: ListAccounts
    private lateinit var notificationPrefs: NotificationPrefsStore
    private lateinit var listId: String

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        db.insertTestAccount(testAccount(serverUrl = server.url("/").toString()))
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")

        listAccounts = testListAccounts(db, listsRepo)

        val notifPrefsFile = File.createTempFile("listprops_vm_notif_prefs", ".preferences_pb")
        notifPrefsFile.deleteOnExit()
        notificationPrefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { notifPrefsFile })
    }

    /**
     * Every view model a test built, so tearDown can stop it (T-286, the pitfall AGENTS.md lists
     * and T-252 fixed in ListViewModelTest). ListPropsViewModel keeps three collectors running on
     * viewModelScope — the list row, the mute preference's DataStore flow, the sync status — and
     * one left running past its test can touch Dispatchers.Main while MainDispatcherRule resets it
     * for the next, which surfaced as a full-suite-only "did not run to completion" timeout on the
     * notification-toggle test.
     */
    private val viewModels = mutableListOf<ListPropsViewModel>()

    @After
    fun tearDown() {
        // Before the rule resets Dispatchers.Main (a rule's finished() runs after @After).
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private fun newViewModel(): ListPropsViewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            listAccounts,
            notificationPrefs,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        ).also(viewModels::add)

    @Test
    fun `initial state loads the name and merges category_order with distinct categories, without a network call`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Nails").also { itemsRepo.setCategory(it, "hardware") }

        val state = newViewModel().uiState.first { it.name.isNotBlank() }

        assertEquals("Groceries", state.name)
        assertEquals(listOf("dairy", "bakery", "hardware"), state.categoryOrder)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `renaming a category onto a different casing of itself just recases, no confirmation needed (T-270)`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setCategoryOrder(listId, listOf("dairy"))
        val milk = itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        val viewModel = newViewModel()
        viewModel.uiState.first { it.categoryOrder.isNotEmpty() }

        viewModel.renameCategory(0, "Dairy")?.join()

        assertNull(viewModel.uiState.value.pendingCategoryMerge)
        assertEquals(listOf("Dairy"), viewModel.uiState.value.categoryOrder)
        assertEquals("Dairy", itemsRepo.getById(milk)!!.category.value)
    }

    @Test
    fun `renaming onto another existing category asks for confirmation instead of merging right away (T-270)`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setCategoryOrder(listId, listOf("Dairy", "Produce"))
        val milk = itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "Dairy") }
        val carrot = itemsRepo.createItem(listId, "Carrot").also { itemsRepo.setCategory(it, "Produce") }
        val viewModel = newViewModel()
        viewModel.uiState.first { it.categoryOrder.isNotEmpty() }

        // Renaming "Produce" to "Dairy" merges the two — held for confirmation rather than
        // applied immediately (the defect this ticket fixes: it used to merge silently here).
        val job = viewModel.renameCategory(1, "Dairy")

        assertNull(job)
        val pending = viewModel.uiState.value.pendingCategoryMerge
        assertNotNull(pending)
        assertEquals("Dairy", pending!!.targetName)
        // Nothing moved yet: the order and the items are untouched until confirmed.
        assertEquals(listOf("Dairy", "Produce"), viewModel.uiState.value.categoryOrder)
        assertEquals("Produce", itemsRepo.getById(carrot)!!.category.value)

        viewModel.confirmCategoryMerge()!!.join()

        assertNull(viewModel.uiState.value.pendingCategoryMerge)
        assertEquals(listOf("Dairy"), viewModel.uiState.value.categoryOrder)
        assertEquals("Dairy", itemsRepo.getById(carrot)!!.category.value)
        assertEquals("Dairy", itemsRepo.getById(milk)!!.category.value)
    }

    @Test
    fun `a confirmed merge renames the category it asked about, not whatever sits at its old index`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setCategoryOrder(listId, listOf("Dairy", "Produce", "Bakery"))
        val carrot = itemsRepo.createItem(listId, "Carrot").also { itemsRepo.setCategory(it, "Produce") }
        val bread = itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "Bakery") }
        val viewModel = newViewModel()
        viewModel.uiState.first { it.categoryOrder.size == 3 }
        viewModel.renameCategory(1, "Dairy") // "Produce" is at index 1 when the question is put
        assertNotNull(viewModel.uiState.value.pendingCategoryMerge)

        // The order moves before the answer comes: Bakery is now at index 1 and Produce at index 2.
        // A rename remembered by index would merge Bakery into Dairy; it must merge Produce.
        viewModel.moveCategoryUp(2)
        assertEquals(listOf("Dairy", "Bakery", "Produce"), viewModel.uiState.value.categoryOrder)

        viewModel.confirmCategoryMerge()!!.join()

        assertEquals("Dairy", itemsRepo.getById(carrot)!!.category.value)
        assertEquals("Bakery", itemsRepo.getById(bread)!!.category.value)
        assertEquals(listOf("Dairy", "Bakery"), viewModel.uiState.value.categoryOrder)
    }

    @Test
    fun `cancelling a pending category merge leaves both categories untouched (T-270)`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setCategoryOrder(listId, listOf("Dairy", "Produce"))
        val carrot = itemsRepo.createItem(listId, "Carrot").also { itemsRepo.setCategory(it, "Produce") }
        val viewModel = newViewModel()
        viewModel.uiState.first { it.categoryOrder.isNotEmpty() }

        viewModel.renameCategory(1, "Dairy")
        assertNotNull(viewModel.uiState.value.pendingCategoryMerge)

        viewModel.cancelCategoryMerge()

        assertNull(viewModel.uiState.value.pendingCategoryMerge)
        assertEquals(listOf("Dairy", "Produce"), viewModel.uiState.value.categoryOrder)
        assertEquals("Produce", itemsRepo.getById(carrot)!!.category.value)
    }

    @Test
    fun `saveName persists a rename as an LWW edit`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        viewModel.onNameChange("Weekly Groceries")
        viewModel.saveName().join()

        assertEquals("Weekly Groceries", listsRepo.getById(listId)!!.name.value)
    }

    @Test
    fun `initial state loads an existing note`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setNotes(listId, "Gate code: 4471")

        val state = newViewModel().uiState.first { it.name.isNotBlank() }

        assertEquals("Gate code: 4471", state.notes)
    }

    @Test
    fun `saveNotes persists notes as an LWW edit`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        viewModel.onNotesChange("Gate code: 4471")
        viewModel.saveNotes().join()

        assertEquals("Gate code: 4471", listsRepo.getById(listId)!!.notes.value)
    }

    @Test
    fun `saveNotes trims whitespace and collapses a blank note to null`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setNotes(listId, "temporary")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        viewModel.onNotesChange("   ")
        viewModel.saveNotes().join()

        assertEquals(null, listsRepo.getById(listId)!!.notes.value)
    }

    @Test
    fun `moveCategoryUp then saveCategoryOrder persists the new order as an LWW edit`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "bakery") }
        val viewModel = newViewModel()
        val loaded = viewModel.uiState.first { it.categoryOrder.isNotEmpty() }
        val originalClock = listsRepo.getById(listId)!!.categoryOrder.updatedAt
        assertEquals(listOf("dairy", "bakery"), loaded.categoryOrder)

        viewModel.moveCategoryUp(1)
        assertEquals(listOf("bakery", "dairy"), viewModel.uiState.value.categoryOrder)

        viewModel.saveCategoryOrder().join()

        val saved = listsRepo.getById(listId)!!
        assertEquals(listOf("bakery", "dairy"), listsRepo.decodeCategoryOrder(saved.categoryOrder.value))
        assertTrue(saved.categoryOrder.updatedAt >= originalClock)
        assertTrue(saved.dirty)
    }

    @Test
    fun `loadMembers populates members and pending invites`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"members": [{"account_id": "acc-a", "email": "a@example.com", "initials": "A", "joined_at": 1}],""" +
                    """"invites": [{"id": "inv-1", "invited_email": "b@example.com", "expires_at": 2}]}""",
            ),
        )

        val viewModel = newViewModel()
        viewModel.loadMembers().join()

        assertEquals(listOf("a@example.com"), viewModel.uiState.value.members.map { it.email })
        assertEquals(listOf("b@example.com"), viewModel.uiState.value.pendingInvites.map { it.invitedEmail })
    }

    /** T-299: the screen holds the list's local id; the server knows the list by its server id. */
    @Test
    fun `every call that names the list names it by its server id`() = runTest(mainDispatcherRule.dispatcher) {
        val serverId = listsRepo.getById(listId)!!.serverId
        assertNotEquals(listId, serverId)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"invite_id": "i", "token": "t", "url": "u", "expires_at": 1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"close_votes": [], "closed_at": null}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        viewModel.loadMembers().join()
        viewModel.onInviteEmailChange("friend@example.com")
        viewModel.sendInvite()?.join()
        viewModel.toggleCloseVote().join()
        viewModel.requestLeave()
        viewModel.confirmLeave().join()

        val paths = (0 until server.requestCount).map { server.takeRequest().path }
        assertEquals(
            listOf("members", "invites", "members", "close-votes", "leave").map { "/api/v1/lists/$serverId/$it" },
            paths,
        )
    }

    @Test
    fun `loadMembers failure surfaces an offline notice instead of crashing`() = runTest(mainDispatcherRule.dispatcher) {
        server.shutdown()

        val viewModel = newViewModel()
        viewModel.loadMembers().join()

        assertNotNull(viewModel.uiState.value.membersError)
    }

    @Test
    fun `sendInvite success shares the URL and refreshes members`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        viewModel.onInviteEmailChange("friend@example.com")
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"invite_id": "inv-1", "token": "tok", "url": "https://host/invite/tok", "expires_at": 1}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"members": [], "invites": [{"id": "inv-1", "invited_email": "friend@example.com", "expires_at": 1}]}""",
            ),
        )

        viewModel.sendInvite()?.join()

        assertEquals("https://host/invite/tok", viewModel.uiState.value.inviteShareUrl)
        assertEquals("", viewModel.uiState.value.inviteEmail)
        assertEquals(listOf("friend@example.com"), viewModel.uiState.value.pendingInvites.map { it.invitedEmail })
    }

    @Test
    fun `sendInvite with a blank email is rejected locally without a network call`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        val job = viewModel.sendInvite()

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `revokeInvite calls the server then refreshes the list`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        viewModel.revokeInvite("inv-1").join()

        assertTrue(viewModel.uiState.value.pendingInvites.isEmpty())
    }

    @Test
    fun `revokeInvite for a list that has gone meanwhile does nothing, and does not crash (T-300)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        // Its account was removed while the screen was open, and the list with it.
        listsRepo.removeLocally(listId)

        viewModel.revokeInvite("inv-1").join()

        assertEquals(0, server.requestCount)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `confirmLeave calls the server and hard-deletes the list and its items locally`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        viewModel.requestLeave()
        assertTrue(viewModel.uiState.value.isLeaveConfirmOpen)
        server.enqueue(MockResponse().setResponseCode(204))

        viewModel.confirmLeave().join()

        assertTrue(viewModel.uiState.value.hasLeft)
        assertFalse(viewModel.uiState.value.isLeaveConfirmOpen)
        assertNull(listsRepo.getById(listId))
        assertNull(itemsRepo.getById(itemId))
    }

    @Test
    fun `confirmLeave offline keeps the list and surfaces an error instead of a zombie delete (T-39)`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.shutdown()

        viewModel.confirmLeave().join()

        // Still a member locally (the server never confirmed), with an actionable error.
        assertFalse(viewModel.uiState.value.hasLeft)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertNotNull(listsRepo.getById(listId))
        assertNotNull(itemsRepo.getById(itemId))
    }

    @Test
    fun `confirmLeave treats a 404 as already-left and cleans up locally (T-39)`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "not_found", "message": "gone"}"""))

        viewModel.confirmLeave().join()

        assertTrue(viewModel.uiState.value.hasLeft)
        assertNull(listsRepo.getById(listId))
        assertNull(itemsRepo.getById(itemId))
    }

    @Test
    fun `toggleCloseVote surfaces a server refusal by code, not the offline message (T-264)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        // A collaborator's vote closed the list while this screen was open — the server, not the
        // network, is why the request failed. ApiException extends IOException, so a catch-order
        // mistake here reported every one of these as "couldn't reach the server".
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error": "list_closed", "message": "closed"}"""))

        viewModel.toggleCloseVote().join()

        assertEquals(UiText.res(R.string.api_error_list_closed), viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isVoting)
    }

    @Test
    fun `toggleCloseVote offline still says offline`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.shutdown()

        viewModel.toggleCloseVote().join()

        assertEquals(UiText.res(R.string.error_offline_retry), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `the per-list notification toggle reflects and writes the mute preference (T-65)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        assertTrue(viewModel.uiState.first { it.notificationsEnabledForList }.notificationsEnabledForList)

        viewModel.setListNotificationsEnabled(false).join()

        assertFalse(viewModel.uiState.first { !it.notificationsEnabledForList }.notificationsEnabledForList)
        assertEquals(setOf(listId), notificationPrefs.mutedListIds.first())

        viewModel.setListNotificationsEnabled(true).join()

        assertTrue(viewModel.uiState.first { it.notificationsEnabledForList }.notificationsEnabledForList)
        assertTrue(notificationPrefs.mutedListIds.first().isEmpty())
    }

    @Test
    fun `duplicateList creates a solo-owned copy with its items and updates state with the new list id (T-63)`() = runTest(mainDispatcherRule.dispatcher) {
        val todoId = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setCategory(todoId, "dairy")
        val deletedId = itemsRepo.createItem(listId, "Old")
        itemsRepo.delete(deletedId)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }

        viewModel.duplicateList().join()

        val newListId = viewModel.uiState.value.duplicatedListId
        assertNotNull(newListId)
        val copy = listsRepo.getById(newListId!!)!!
        assertEquals("Groceries (Copy)", copy.name.value)
        val copiedItems = itemsRepo.itemsForList(newListId).first()
        assertEquals(listOf("Milk"), copiedItems.map { it.name.value })
        assertEquals("dairy", copiedItems.single().category.value)
    }

    @Test
    fun `revokeInvite offline surfaces an error and does not silently no-op (T-39)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.name.isNotBlank() }
        server.shutdown()

        viewModel.revokeInvite("inv-1").join()

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `checkedCount counts checked items regardless of category (T-75)`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)

        val state = newViewModel().uiState.first { it.checkedCount == 2 }

        assertEquals(2, state.checkedCount)
    }

    @Test
    fun `clearChecked moves every checked item to backlog and drops the count to zero (T-75)`() = runTest(mainDispatcherRule.dispatcher) {
        val milk = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val eggs = itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        val bread = itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.checkedCount == 2 }

        viewModel.clearChecked().join()

        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(milk)!!.status.value)
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(eggs)!!.status.value)
        // The todo item is untouched, and the live count falls to 0 (hiding the button).
        assertEquals(Status.TODO.wireValue, itemsRepo.getById(bread)!!.status.value)
        assertEquals(0, viewModel.uiState.first { it.checkedCount == 0 }.checkedCount)
    }
}
