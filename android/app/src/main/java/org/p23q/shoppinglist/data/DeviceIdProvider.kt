package org.p23q.shoppinglist.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

/** Source of the local device's id, stamped into every field clock this device writes. */
fun interface DeviceIdProvider {
    fun get(): String
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceIdModule {
    // A3 replaces this with a DataStore-persisted id (Notes: deviceId minted once); until then
    // this only survives for the process lifetime, which is fine since nothing syncs yet.
    @Provides
    @Singleton
    fun provideDeviceIdProvider(): DeviceIdProvider {
        val id = UUID.randomUUID().toString()
        return DeviceIdProvider { id }
    }
}
