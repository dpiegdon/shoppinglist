package org.p23q.shoppinglist.ui.item

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

@RunWith(RobolectricTestRunner::class)
class ItemFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var sessionState: FakeSessionState
    private lateinit var listId: String

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        sessionState = FakeSessionState().apply { defaultCurrency = "USD" }
        listId = listsRepo.createList("Groceries")
    }

    private fun newViewModel(): ItemFormViewModel = ItemFormViewModel(itemsRepo, listsRepo, sessionState)

    @Test
    fun `editing a category to the same word with different case recases the whole category (T-108)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val id1 = itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "group") }
            val id2 = itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "group") }
            val viewModel = newViewModel()
            viewModel.startEdit(id1).join()

            viewModel.onCategoryChange("Group")
            viewModel.save()?.join()

            // Both items in the "group" category are recased, not just the edited one.
            assertEquals("Group", itemsRepo.getById(id1)!!.category.value)
            assertEquals("Group", itemsRepo.getById(id2)!!.category.value)
        }

    @Test
    fun `editing a category to a different word only moves that one item (T-108)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val id1 = itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
            val id2 = itemsRepo.createItem(listId, "Butter").also { itemsRepo.setCategory(it, "dairy") }
            val viewModel = newViewModel()
            viewModel.startEdit(id1).join()

            viewModel.onCategoryChange("fridge")
            viewModel.save()?.join()

            assertEquals("fridge", itemsRepo.getById(id1)!!.category.value)
            assertEquals("dairy", itemsRepo.getById(id2)!!.category.value) // untouched
        }

    @Test
    fun `suggestions narrow as the name is typed, case-insensitively, across every status`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        itemsRepo.createItem(listId, "milk chocolate", status = Status.BACKLOG)
        itemsRepo.createItem(listId, "Bread", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        viewModel.onNameChange("MILK")

        val suggestions = viewModel.uiState.first { it.suggestions.size == 2 }.suggestions
        assertEquals(setOf("Milk", "milk chocolate"), suggestions.map { it.name.value }.toSet())
    }

    @Test
    fun `backlog items are suggested while the Name field is still blank (T-52)`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Flour", status = Status.BACKLOG)
        itemsRepo.createItem(listId, "Sugar", status = Status.TODO)
        itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        val viewModel = newViewModel()

        viewModel.startAdd(listId)

        val suggestions = viewModel.uiState.first { it.suggestions.isNotEmpty() }.suggestions
        assertEquals(listOf("Flour"), suggestions.map { it.name.value })
    }

    @Test
    fun `backlog suggestions are ordered most-recently-touched first (T-52)`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Flour", status = Status.BACKLOG)
        Thread.sleep(2)
        itemsRepo.createItem(listId, "Sugar", status = Status.BACKLOG)
        Thread.sleep(2)
        itemsRepo.createItem(listId, "Salt", status = Status.BACKLOG)
        val viewModel = newViewModel()

        viewModel.startAdd(listId)

        val suggestions = viewModel.uiState.first { it.suggestions.size == 3 }.suggestions
        assertEquals(listOf("Salt", "Sugar", "Flour"), suggestions.map { it.name.value })
    }

    @Test
    fun `typing replaces backlog suggestions with a name search across every status (T-52)`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Flour", status = Status.BACKLOG)
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.uiState.first { it.suggestions.any { s -> s.name.value == "Flour" } }

        viewModel.onNameChange("Milk")

        // Both the stale backlog suggestion and the new search result happen to have size 1, so
        // sizing alone can't distinguish "already updated" from "still the old emission" -
        // match on content instead, or first{} can return immediately on the stale value.
        val suggestions = viewModel.uiState.first { it.suggestions.any { s -> s.name.value == "Milk" } }.suggestions
        assertEquals(listOf("Milk"), suggestions.map { it.name.value })
    }

    @Test
    fun `clearing the Name field back to blank restores backlog suggestions (T-52)`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Flour", status = Status.BACKLOG)
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("something")
        viewModel.uiState.first { it.suggestions.isEmpty() }

        viewModel.onNameChange("")

        val suggestions = viewModel.uiState.first { it.suggestions.isNotEmpty() }.suggestions
        assertEquals(listOf("Flour"), suggestions.map { it.name.value })
    }

    @Test
    fun `edit mode never shows backlog suggestions`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Flour", status = Status.BACKLOG)
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = newViewModel()

        viewModel.startEdit(itemId).join()

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `picking a suggestion prefills the form and binds the itemId WITHOUT mutating the item`() = runTest(mainDispatcherRule.dispatcher) {
        val existingId = itemsRepo.createItem(listId, "Milk", status = Status.BACKLOG)
        itemsRepo.setCategory(existingId, "dairy")
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        val existing = itemsRepo.getById(existingId)!!

        viewModel.pickSuggestion(existing)

        // The item is untouched until Save — picking then cancelling (never saving) must not put
        // it on the list (T-33). Its status stays backlog.
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(existingId)!!.status.value)
        assertEquals(existingId, viewModel.uiState.value.itemId)
        assertEquals("Milk", viewModel.uiState.value.name)
        assertEquals("dairy", viewModel.uiState.value.category)
    }

    @Test
    fun `saving a picked suggestion puts that existing item on the list as todo`() = runTest(mainDispatcherRule.dispatcher) {
        val existingId = itemsRepo.createItem(listId, "Milk", status = Status.BACKLOG)
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.pickSuggestion(itemsRepo.getById(existingId)!!)

        viewModel.save()?.join()

        // Same item (no duplicate created), now on the list.
        assertEquals(Status.TODO.wireValue, itemsRepo.getById(existingId)!!.status.value)
        assertEquals(1, itemsRepo.searchRegistry(listId, "Milk").first().size)
    }

    @Test
    fun `saveAndAddAnother creates the item then resets the form without closing (T-41)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("Milk")

        viewModel.saveAndAddAnother()?.join()

        // The item was created...
        assertEquals(1, itemsRepo.searchRegistry(listId, "Milk").first().size)
        // ...but the dialog stays open on a fresh form (not isSaved) and asks to refocus Name.
        assertFalse(viewModel.uiState.value.isSaved)
        assertEquals("", viewModel.uiState.value.name)
        assertEquals(1, viewModel.uiState.value.focusNameSignal)
    }

    @Test
    fun `save with no picked suggestion creates a new todo item with the entered fields`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("Milk")
        viewModel.onCategoryChange("dairy")
        viewModel.onQuantityChange("2l")

        viewModel.save()?.join()

        assertTrue(viewModel.uiState.value.isSaved)
        val created = itemsRepo.searchRegistry(listId, "Milk").first().single()
        assertEquals(Status.TODO.wireValue, created.status.value)
        assertEquals("dairy", created.category.value)
        assertEquals("2l", created.quantity.value)
    }

    @Test
    fun `save is blocked with an inline error when the typed name collides with another item`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("milk")

        viewModel.save()?.join()

        assertNotNull(viewModel.uiState.value.nameError)
        assertFalse(viewModel.uiState.value.isSaved)
        assertEquals(1, itemsRepo.searchRegistry(listId, "milk").first().size)
    }

    @Test
    fun `startEdit prefills every field from the existing item`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setCategory(itemId, "dairy")
        itemsRepo.setStores(itemId, listOf("Rewe", "Aldi"))
        itemsRepo.setQuantity(itemId, "2l")
        itemsRepo.setPrice(itemId, amount = "1.99", currency = "EUR")
        itemsRepo.setNote(itemId, "the ripe ones")
        val viewModel = newViewModel()

        viewModel.startEdit(itemId).join()

        val state = viewModel.uiState.value
        assertTrue(state.isEditMode)
        assertEquals("Milk", state.name)
        assertEquals("dairy", state.category)
        assertEquals(listOf("Rewe", "Aldi"), state.stores)
        assertEquals("2l", state.quantity)
        assertEquals("1.99", state.priceAmount)
        assertEquals("EUR", state.priceCurrency)
        assertEquals("the ripe ones", state.note)
    }

    @Test
    fun `renaming to a name already used by another item is blocked`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Bread")
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.onNameChange("bread")
        viewModel.save()?.join()

        assertNotNull(viewModel.uiState.value.nameError)
        assertEquals("Milk", itemsRepo.getById(itemId)!!.name.value)
    }

    @Test
    fun `renaming an item to its own current name (any case) is not a collision`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.onNameChange("milk")
        viewModel.onQuantityChange("1l")
        viewModel.save()?.join()

        assertTrue(viewModel.uiState.value.isSaved)
        assertNull(viewModel.uiState.value.nameError)
        assertEquals("1l", itemsRepo.getById(itemId)!!.quantity.value)
    }

    @Test
    fun `edit save persists field edits including status`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.onNameChange("Whole Milk")
        viewModel.onStatusChange(Status.BACKLOG)
        viewModel.save()?.join()

        val saved = itemsRepo.getById(itemId)!!
        assertEquals("Whole Milk", saved.name.value)
        assertEquals(Status.BACKLOG.wireValue, saved.status.value)
    }

    @Test
    fun `confirmDelete tombstones the item`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.requestDelete()
        assertTrue(viewModel.uiState.value.isDeleteConfirmOpen)

        viewModel.confirmDelete()?.join()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertTrue(itemsRepo.getById(itemId)!!.deleted.value)
    }

    @Test
    fun `addStore appends a chip and removeStore drops it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        viewModel.onStoreInputChange("Rewe")
        viewModel.addStore()
        viewModel.onStoreInputChange("Aldi")
        viewModel.addStore()
        assertEquals(listOf("Rewe", "Aldi"), viewModel.uiState.value.stores)
        assertEquals("", viewModel.uiState.value.storeInput)

        viewModel.removeStore("Rewe")
        assertEquals(listOf("Aldi"), viewModel.uiState.value.stores)
    }

    // ---- price validation / normalization (T-32) ------------------------------------------

    @Test
    fun `parsePriceAmount normalizes comma decimals and strips currency symbols and spaces`() {
        assertEquals("1.99", (parsePriceAmount("1,99") as PriceParse.Valid).value)
        assertEquals("2", (parsePriceAmount("2€") as PriceParse.Valid).value)
        assertEquals("1.50", (parsePriceAmount("  1.50 ") as PriceParse.Valid).value)
        assertNull((parsePriceAmount("   ") as PriceParse.Valid).value)
    }

    @Test
    fun `parsePriceAmount rejects too many decimals and non-numeric input`() {
        assertTrue(parsePriceAmount("1.999") is PriceParse.Invalid)
        assertTrue(parsePriceAmount("abc") is PriceParse.Invalid)
    }

    @Test
    fun `parseCurrency uppercases a valid code and rejects the wrong length`() {
        assertEquals("EUR", (parseCurrency("eur") as PriceParse.Valid).value)
        assertNull((parseCurrency("") as PriceParse.Valid).value)
        assertTrue(parseCurrency("EU") is PriceParse.Invalid)
    }

    @Test
    fun `saving a comma-decimal price stores it normalized`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("Milk")
        viewModel.onPriceAmountChange("1,99")
        viewModel.onPriceCurrencyChange("eur")

        viewModel.save()?.join()

        val stored = itemsRepo.findByExactName(listId, "Milk")!!
        val price = itemsRepo.decodePrice(stored.price.value)!!
        assertEquals("1.99", price.amount)
        assertEquals("EUR", price.currency)
    }

    @Test
    fun `an invalid price is rejected inline and never written (does not reach sync)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("Milk")
        viewModel.onPriceAmountChange("1.999")

        val job = viewModel.save()

        assertNull("synchronous validation failure returns no Job", job)
        assertEquals(UiText.res(R.string.item_msg_price_invalid), viewModel.uiState.value.priceError)
        assertFalse(viewModel.uiState.value.isSaved)
        assertNull("nothing was written to the mirror", itemsRepo.findByExactName(listId, "Milk"))
    }

    // ---- change-scoped saves (T-88): only re-stamp fields the user actually changed ------------

    /**
     * A second repo over the SAME dao but a distinct device id, used to seed an item. Every field it
     * writes carries "seed-device"; a field the ViewModel (device-1) re-stamps flips to "device-1",
     * so per-field [org.p23q.shoppinglist.data.db.LwwString.updatedBy] tells us exactly which setters ran.
     */
    private fun seedRepo(device: String): ItemsRepo =
        ItemsRepo(db.itemDao(), DeviceIdProvider { device }, FakeSyncTrigger())

    @Test
    fun `edit changing only the note re-stamps only the note field (T-88)`() = runTest(mainDispatcherRule.dispatcher) {
        val seed = seedRepo("seed-device")
        val itemId = seed.createItem(listId, "Milk", status = Status.TODO)
        seed.setCategory(itemId, "dairy")
        seed.setStores(itemId, listOf("Rewe"))
        seed.setQuantity(itemId, "2l")
        seed.setPrice(itemId, amount = "1.99", currency = "EUR")
        seed.setNote(itemId, "old note")
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.onNoteChange("fresh note")
        viewModel.save()?.join()

        val saved = itemsRepo.getById(itemId)!!
        assertTrue(viewModel.uiState.value.isSaved)
        assertEquals("fresh note", saved.note.value)
        // Only the note carries this device's stamp; every untouched field keeps the seed clock.
        assertEquals("device-1", saved.note.updatedBy)
        assertEquals("seed-device", saved.name.updatedBy)
        assertEquals("seed-device", saved.category.updatedBy)
        assertEquals("seed-device", saved.stores.updatedBy)
        assertEquals("seed-device", saved.quantity.updatedBy)
        assertEquals("seed-device", saved.price.updatedBy)
        assertEquals("seed-device", saved.status.updatedBy)
    }

    @Test
    fun `adopting an unmodified suggestion re-stamps only the status (T-88)`() = runTest(mainDispatcherRule.dispatcher) {
        val seed = seedRepo("seed-device")
        val existingId = seed.createItem(listId, "Cheese", status = Status.BACKLOG)
        seed.setCategory(existingId, "dairy")
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.pickSuggestion(itemsRepo.getById(existingId)!!)

        viewModel.save()?.join()

        val saved = itemsRepo.getById(existingId)!!
        // The adopt flips backlog -> todo (that one field is stamped) and touches nothing else.
        assertEquals(Status.TODO.wireValue, saved.status.value)
        assertEquals("device-1", saved.status.updatedBy)
        assertEquals("seed-device", saved.name.updatedBy)
        assertEquals("seed-device", saved.category.updatedBy)
        assertEquals("seed-device", saved.stores.updatedBy)
        assertEquals("seed-device", saved.quantity.updatedBy)
        assertEquals("seed-device", saved.price.updatedBy)
        assertEquals("seed-device", saved.note.updatedBy)
    }

    @Test
    fun `a zero-change edit writes nothing but still closes (T-88)`() = runTest(mainDispatcherRule.dispatcher) {
        val seed = seedRepo("seed-device")
        val itemId = seed.createItem(listId, "Milk", status = Status.TODO)
        seed.setCategory(itemId, "dairy")
        seed.setNote(itemId, "keep me")
        // Clear the creation dirty flag so a later dirty row can only come from a save write.
        itemsRepo.clearDirty(itemsRepo.dirtyRows().map { it.id })
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.save()?.join()

        assertTrue(viewModel.uiState.value.isSaved)
        val saved = itemsRepo.getById(itemId)!!
        // No setter ran: every field keeps the seed clock and the row is still not dirty.
        assertEquals("seed-device", saved.name.updatedBy)
        assertEquals("seed-device", saved.category.updatedBy)
        assertEquals("seed-device", saved.status.updatedBy)
        assertEquals("seed-device", saved.note.updatedBy)
        assertFalse(saved.dirty)
    }
}
