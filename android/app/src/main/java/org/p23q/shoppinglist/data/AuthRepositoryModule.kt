package org.p23q.shoppinglist.data

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.AuthRepositoryImpl
import org.p23q.shoppinglist.core.DefaultCurrencyState
import org.p23q.shoppinglist.core.SessionState
import org.p23q.shoppinglist.core.api.ApiSource
import org.p23q.shoppinglist.core.db.AppDb
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthRepositoryModule {
    @Provides
    @Singleton
    fun provideAuthRepository(
        apiSource: ApiSource,
        sessionState: SessionState,
        appDb: AppDb,
        defaultCurrencyState: DefaultCurrencyState,
    ): AuthRepository = AuthRepositoryImpl(
        apiSource,
        sessionState,
        appDb,
        defaultCurrencyState,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
    )
}
