package org.p23q.shoppinglist.data

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.AuthRepositoryImpl
import org.p23q.shoppinglist.core.DefaultCurrencyState
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.db.AppDb
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthRepositoryModule {
    @Provides
    @Singleton
    fun provideAuthRepository(
        sessions: AccountSessions,
        registry: AccountRegistry,
        secrets: SecretStore,
        lastOpened: LastOpenedListStore,
        appDb: AppDb,
        defaultCurrencyState: DefaultCurrencyState,
    ): AuthRepository = AuthRepositoryImpl(
        sessions,
        registry,
        secrets,
        lastOpened,
        appDb,
        defaultCurrencyState,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
    )
}
