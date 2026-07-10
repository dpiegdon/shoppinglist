package org.p23q.shoppinglist.ui.item

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
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
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        sessionState = FakeSessionState().apply { defaultCurrency = "USD" }
        listId = listsRepo.createList("Groceries")
    }

    private fun newViewModel(): ItemFormViewModel = ItemFormViewModel(itemsRepo, sessionState)

    @Test
    fun `suggestions narrow as the name is typed, case-insensitively, across every status`() = runTest {
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
    fun `picking a suggestion sets it todo, prefills the form, and binds the itemId`() = runTest {
        val existingId = itemsRepo.createItem(listId, "Milk", status = Status.BACKLOG)
        itemsRepo.setCategory(existingId, "dairy")
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        val existing = itemsRepo.getById(existingId)!!

        viewModel.pickSuggestion(existing).join()

        assertEquals(Status.TODO.wireValue, itemsRepo.getById(existingId)!!.status.value)
        assertEquals(existingId, viewModel.uiState.value.itemId)
        assertEquals("Milk", viewModel.uiState.value.name)
        assertEquals("dairy", viewModel.uiState.value.category)
    }

    @Test
    fun `save with no picked suggestion creates a new todo item with the entered fields`() = runTest {
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
    fun `save is blocked with an inline error when the typed name collides with another item`() = runTest {
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
    fun `startEdit prefills every field from the existing item`() = runTest {
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
    fun `renaming to a name already used by another item is blocked`() = runTest {
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
    fun `renaming an item to its own current name (any case) is not a collision`() = runTest {
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
    fun `edit save persists field edits including status`() = runTest {
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
    fun `confirmDelete tombstones the item`() = runTest {
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
    fun `addStore appends a chip and removeStore drops it`() = runTest {
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
    fun `saving a comma-decimal price stores it normalized`() = runTest {
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
    fun `an invalid price is rejected inline and never written (does not reach sync)`() = runTest {
        val viewModel = newViewModel()
        viewModel.startAdd(listId)
        viewModel.onNameChange("Milk")
        viewModel.onPriceAmountChange("1.999")

        val job = viewModel.save()

        assertNull("synchronous validation failure returns no Job", job)
        assertEquals("Enter an amount like 1.99", viewModel.uiState.value.priceError)
        assertFalse(viewModel.uiState.value.isSaved)
        assertNull("nothing was written to the mirror", itemsRepo.findByExactName(listId, "Milk"))
    }
}
