package org.p23q.shoppinglist.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Source of the local device's id, stamped into every field clock this device writes. */
fun interface DeviceIdProvider {
    suspend fun get(): String
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceIdModule {
    @Provides
    @Singleton
    fun provideDeviceIdProvider(serverConfig: ServerConfig): DeviceIdProvider =
        DeviceIdProvider { serverConfig.deviceId() }
}
