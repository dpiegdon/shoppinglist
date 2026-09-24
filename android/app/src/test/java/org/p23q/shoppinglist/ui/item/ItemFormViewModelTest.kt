package org.p23q.shoppinglist.ui.item

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File

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
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
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
    fun `picking a suggestion puts that existing item on the list right away (T-140)`() = runTest(mainDispatcherRule.dispatcher) {
        val existingId = itemsRepo.createItem(listId, "Milk", status = Status.BACKLOG)
        itemsRepo.setCategory(existingId, "dairy")
        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        viewModel.pickSuggestion(itemsRepo.getById(existingId)!!)?.join()

        // No second press: the pick IS the add. Same item (no duplicate), now on the list, and the
        // dialog is done — isSaved is what AddItemDialog closes on.
        assertEquals(Status.TODO.wireValue, itemsRepo.getById(existingId)!!.status.value)
        assertEquals(1, itemsRepo.searchRegistry(listId, "Milk").first().size)
        assertEquals(existingId, viewModel.uiState.value.itemId)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `picking an item already on the list writes nothing but still closes (T-140)`() = runTest(mainDispatcherRule.dispatcher) {
        val seed = seedRepo("seed-device")
        val existingId = seed.createItem(listId, "Milk", status = Status.TODO)
        // Clear the creation dirty flag so a later dirty row can only come from a pick write.
        itemsRepo.clearDirty(itemsRepo.dirtyRows().map { it.id })
        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        viewModel.pickSuggestion(itemsRepo.getById(existingId)!!)?.join()

        // Adopting is change-scoped, so re-adding something already todo is a no-op rather than a
        // pointless new status clock — and it must not push a row for nothing.
        val saved = itemsRepo.getById(existingId)!!
        assertEquals("seed-device", saved.status.updatedBy)
        assertFalse(saved.dirty)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `existing stores on the list are offered as suggestions (T-138)`() = runTest(mainDispatcherRule.dispatcher) {
        val milk = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setStores(milk, listOf("Aldi", "Rewe"))
        val bread = itemsRepo.createItem(listId, "Bread")
        itemsRepo.setStores(bread, listOf("Rewe"))

        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        // Flattened across items and deduplicated: stores_value is a JSON array per row, so "Rewe"
        // appears twice in the raw data.
        assertEquals(listOf("Aldi", "Rewe"), viewModel.uiState.first { it.storeSuggestions.isNotEmpty() }.storeSuggestions)
    }

    @Test
    fun `store suggestions collapse casing variants into one entry (T-138)`() = runTest(mainDispatcherRule.dispatcher) {
        val a = itemsRepo.createItem(listId, "A")
        itemsRepo.setStores(a, listOf("Aldi"))
        val b = itemsRepo.createItem(listId, "B")
        itemsRepo.setStores(b, listOf("aldi"))
        val c = itemsRepo.createItem(listId, "C")
        itemsRepo.setStores(c, listOf("Aldi"))

        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        // One chip, in the casing that appears most often — offering "Aldi" and "aldi" separately
        // is how you end up with both on real items.
        assertEquals(listOf("Aldi"), viewModel.uiState.first { it.storeSuggestions.isNotEmpty() }.storeSuggestions)
    }

    @Test
    fun `picking a store adds it, and never twice (T-138)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        viewModel.pickStore("Aldi")
        assertEquals(listOf("Aldi"), viewModel.uiState.value.stores)

        // Same store by a different casing — the guard is case-insensitive, so an item can't end
        // up carrying "Aldi" and "aldi" as two chips.
        viewModel.pickStore("aldi")
        assertEquals(listOf("Aldi"), viewModel.uiState.value.stores)
    }

    @Test
    fun `the plus button still adds and clears the field, and skips duplicates (T-138)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)

        viewModel.onStoreInputChange("  Rewe  ")
        viewModel.addStore()
        assertEquals(listOf("Rewe"), viewModel.uiState.value.stores)
        assertEquals("", viewModel.uiState.value.storeInput)

        viewModel.onStoreInputChange("REWE")
        viewModel.addStore()
        assertEquals(listOf("Rewe"), viewModel.uiState.value.stores)
        assertEquals("", viewModel.uiState.value.storeInput)
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

    // Driven by the table the web's priceParse.test.ts reads too (T-275): this client used to
    // strip a currency symbol from anywhere in the string, so "1€5" saved as 15.00 here while the
    // web (correctly) rejected it. The ends-only grammar is now shared by both.
    @Test
    fun `parsePriceAmount matches the shared table`() {
        val file = File("../../shared-test-cases/price-parse.json")
        assertTrue("missing shared case table at ${file.absolutePath}", file.exists())
        val cases: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
        val amountCases = cases["amount"]!!.jsonArray.map { it.jsonObject }
        assertTrue("the case table covers every group this test drives", amountCases.isNotEmpty())

        for (case in amountCases) {
            val name = case["name"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonPrimitive.content
            val valid = case["valid"]!!.jsonPrimitive.boolean
            val result = parsePriceAmount(input)
            if (valid) {
                val value = case["value"]?.jsonPrimitive?.contentOrNull
                assertEquals(name, value, (result as? PriceParse.Valid)?.value)
            } else {
                assertTrue(name, result is PriceParse.Invalid)
            }
        }
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
     * so per-field [org.p23q.shoppinglist.core.db.LwwString.updatedBy] tells us exactly which setters ran.
     */
    private fun seedRepo(device: String): ItemsRepo =
        ItemsRepo(db, DeviceIdProvider { device }, FakeSyncTrigger())

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
        viewModel.pickSuggestion(itemsRepo.getById(existingId)!!)?.join()

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
