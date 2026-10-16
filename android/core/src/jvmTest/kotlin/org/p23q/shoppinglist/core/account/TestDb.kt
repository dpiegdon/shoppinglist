package org.p23q.shoppinglist.core.account

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.db.ListEntity
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional

/** An in-memory database on the bundled native SQLite, the JVM's only one. */
internal fun testDb(): AppDb = Room.inMemoryDatabaseBuilder<AppDb>()
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()

internal fun account(id: String, serverUrl: String = "https://$id.example.test/", signedIn: Boolean = true) =
    AccountEntity(id = id, serverUrl = serverUrl, accountId = "server-$id", email = "$id@example.com", label = id, signedIn = signedIn)

/** A list with local id [id]; its server id differs from it unless given. */
internal fun list(id: String, accountId: String, serverId: String = "srv-$id") = ListEntity(
    localId = id,
    serverId = serverId,
    accountId = accountId,
    createdAt = 1,
    name = id.toLww("dev", 1),
    categoryOrder = "[]".toLww("dev", 1),
    notes = null.toLwwOptional("dev", 1),
    kind = "shopping".toLww("dev", 1),
    deleted = false.toLww("dev", 1),
    dirty = false,
)

/** An item with local id [id] on the list with local id [listId], of that list's [accountId]. */
internal fun item(id: String, listId: String, accountId: String, serverId: String = "srv-$id") = ItemEntity(
    localId = id,
    serverId = serverId,
    accountId = accountId,
    listLocalId = listId,
    createdAt = 1,
    name = id.toLww("dev", 1),
    category = null.toLwwOptional("dev", 1),
    stores = "[]".toLww("dev", 1),
    quantity = null.toLwwOptional("dev", 1),
    price = null.toLwwOptional("dev", 1),
    note = null.toLwwOptional("dev", 1),
    status = "todo".toLww("dev", 1),
    deleted = false.toLww("dev", 1),
    dirty = true,
)

internal class MapSecretStore : SecretStore, LastOpenedListStore {
    val tokens = mutableMapOf<String, String>()
    override var lastOpenedListId: String? = null
    override fun token(accountId: String) = tokens[accountId]
    override fun setToken(accountId: String, token: String?) {
        if (token == null) tokens.remove(accountId) else tokens[accountId] = token
    }
}
