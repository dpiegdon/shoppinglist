package org.p23q.shoppinglist.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemDao
import org.p23q.shoppinglist.core.db.ListDao
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.SyncTrigger
import javax.inject.Singleton

/**
 * The repositories, the sync engine and the sync status from :core, which carries no injection
 * annotations. Only [SyncStatus] is one per process; the others are built for each injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun provideSyncStatus(): SyncStatus = SyncStatus()

    @Provides
    fun provideListsRepo(db: AppDb, deviceId: DeviceIdProvider, syncTrigger: SyncTrigger): ListsRepo =
        ListsRepo(db, deviceId, syncTrigger)

    @Provides
    fun provideItemsRepo(db: AppDb, deviceId: DeviceIdProvider, syncTrigger: SyncTrigger): ItemsRepo =
        ItemsRepo(db, deviceId, syncTrigger)

    @Provides
    fun provideSyncEngine(
        itemDao: ItemDao,
        listDao: ListDao,
        registry: AccountRegistry,
        sessions: AccountSessions,
        deviceId: DeviceIdProvider,
        db: AppDb,
        syncStatus: SyncStatus,
        notifier: CollaboratorChangeNotifier,
    ): SyncEngine = SyncEngine(itemDao, listDao, registry, sessions, deviceId, db, syncStatus, notifier)
}
