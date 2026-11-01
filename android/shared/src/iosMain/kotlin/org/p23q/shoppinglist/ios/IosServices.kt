package org.p23q.shoppinglist.ios

import org.koin.mp.KoinPlatform
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer

/**
 * The services the Swift side may need beside the screens, resolved from Koin after
 * [startIosApp]: Koin's generic `get` has no Swift spelling, so each is named here. The app's
 * XCTests drive the platform layer through these (login, lists, items, sync, the Keychain) where
 * the screens cannot be shown, and a later background-refresh task (T-354) syncs through
 * [syncer].
 */
object IosServices {
    private val koin get() = KoinPlatform.getKoin()

    val auth: AuthRepository get() = koin.get()
    val registry: AccountRegistry get() = koin.get()
    val lists: ListsRepo get() = koin.get()
    val items: ItemsRepo get() = koin.get()
    val syncer: Syncer get() = koin.get()
    val syncStatus: SyncStatus get() = koin.get()
    val secrets: SecretStore get() = koin.get()
}
