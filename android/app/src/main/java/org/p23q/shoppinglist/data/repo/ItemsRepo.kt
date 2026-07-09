package org.p23q.shoppinglist.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.ItemDao
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.db.toLww
import org.p23q.shoppinglist.data.db.toLwwOptional
import java.util.UUID
import javax.inject.Inject

@Serializable
data class Price(val amount: String, val currency: String?)

class ItemsRepo @Inject constructor(
    private val itemDao: ItemDao,
    private val deviceId: DeviceIdProvider,
) {
    fun itemsForListByStatus(listId: String, status: Status): Flow<List<ItemEntity>> =
        itemDao.itemsForListByStatus(listId, status.wireValue)

    fun searchRegistry(listId: String, nameQuery: String): Flow<List<ItemEntity>> =
        itemDao.searchRegistry(listId, nameQuery)

    fun distinctCategories(listId: String): Flow<List<String>> = itemDao.distinctCategories(listId)

    suspend fun getById(itemId: String): ItemEntity? = itemDao.getById(itemId)

    suspend fun dirtyRows(): List<ItemEntity> = itemDao.dirtyRows()

    suspend fun clearDirty(ids: List<String>) = itemDao.clearDirty(ids)

    suspend fun createItem(listId: String, name: String, status: Status = Status.TODO): String {
        val id = UUID.randomUUID().toString()
        val by = deviceId.get()
        val now = System.currentTimeMillis()
        itemDao.upsert(
            ItemEntity(
                id = id,
                listId = listId,
                createdAt = now,
                name = name.toLww(by, now),
                category = null.toLwwOptional(by, now),
                stores = encodeStores(emptyList()).toLww(by, now),
                quantity = null.toLwwOptional(by, now),
                price = null.toLwwOptional(by, now),
                note = null.toLwwOptional(by, now),
                status = status.wireValue.toLww(by, now),
                deleted = false.toLww(by, now),
                dirty = true,
            ),
        )
        return id
    }

    suspend fun rename(itemId: String, name: String) =
        updateField(itemId) { it.copy(name = name.toLww(deviceId.get())) }

    suspend fun setStatus(itemId: String, status: Status) =
        updateField(itemId) { it.copy(status = status.wireValue.toLww(deviceId.get())) }

    suspend fun setCategory(itemId: String, category: String?) =
        updateField(itemId) { it.copy(category = category.toLwwOptional(deviceId.get())) }

    suspend fun setStores(itemId: String, stores: List<String>) =
        updateField(itemId) { it.copy(stores = encodeStores(stores).toLww(deviceId.get())) }

    suspend fun setQuantity(itemId: String, quantity: String?) =
        updateField(itemId) { it.copy(quantity = quantity.toLwwOptional(deviceId.get())) }

    suspend fun setPrice(itemId: String, amount: String?, currency: String?) =
        updateField(itemId) {
            val json = amount?.let { Json.encodeToString(Price(it, currency)) }
            it.copy(price = json.toLwwOptional(deviceId.get()))
        }

    suspend fun setNote(itemId: String, note: String?) =
        updateField(itemId) { it.copy(note = note.toLwwOptional(deviceId.get())) }

    /** Tombstone: [ItemEntity.deleted] flips true, the row itself is retained for sync/undo. */
    suspend fun delete(itemId: String) = updateField(itemId) { it.copy(deleted = true.toLww(deviceId.get())) }

    fun decodeStores(json: String): List<String> = Json.decodeFromString(json)

    fun decodePrice(json: String?): Price? = json?.let { Json.decodeFromString(it) }

    private fun encodeStores(stores: List<String>): String = Json.encodeToString(stores)

    private suspend fun updateField(itemId: String, mutate: suspend (ItemEntity) -> ItemEntity) {
        val current = itemDao.getById(itemId) ?: return
        itemDao.upsert(mutate(current).copy(dirty = true))
    }
}
