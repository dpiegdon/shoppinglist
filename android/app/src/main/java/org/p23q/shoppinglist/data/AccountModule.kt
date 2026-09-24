package org.p23q.shoppinglist.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.data.api.RetrofitApiFactory
import javax.inject.Singleton

/** The accounts and their API clients, from :core, wired to :app's platform implementations. */
@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    @Provides
    @Singleton
    fun provideAccountRegistry(db: AppDb): AccountRegistry = AccountRegistry(db)

    @Provides
    @Singleton
    fun provideAccountSessions(
        registry: AccountRegistry,
        secrets: SecretStore,
        apiFactory: RetrofitApiFactory,
        json: Json,
        syncStatus: SyncStatus,
    ): AccountSessions = AccountSessions(registry, secrets, apiFactory, json, syncStatus)
}
