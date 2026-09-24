package org.p23q.shoppinglist.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.DeviceIdProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceIdModule {
    @Provides
    @Singleton
    fun provideDeviceIdProvider(serverConfig: ServerConfig): DeviceIdProvider =
        DeviceIdProvider { serverConfig.deviceId() }
}
