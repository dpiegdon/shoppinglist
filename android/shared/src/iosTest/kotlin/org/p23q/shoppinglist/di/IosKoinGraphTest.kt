package org.p23q.shoppinglist.di

import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.dsl.koinApplication
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.AppFiles
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Koin graph the iOS app starts with is complete, as :app's KoinGraphTest checks Android's:
 * every definition but the view models resolves, with the files in a temporary directory. The
 * view models need a ViewModelStore; their constructors ask for the same types as on Android,
 * where Koin's static check covers them.
 */
class IosKoinGraphTest {

    private var app: KoinApplication? = null

    @AfterTest
    fun tearDown() {
        app?.close()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every definition resolves`() {
        val koin = startApp().koin
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }.distinct()
            .filterNot { it.primaryType.simpleName.orEmpty().endsWith("ViewModel") }
        assertTrue(definitions.size > 25, "the definitions are found: ${definitions.size}")

        for (definition in definitions) {
            assertNotNull(resolve(koin, definition), "$definition")
        }
    }

    @Test
    fun `singles are one per process and factories new each time`() {
        val koin = startApp().koin

        assertTrue(koin.get<SyncStatus>() === koin.get<SyncStatus>())
        assertTrue(koin.get<Syncer>() !== koin.get<Syncer>())
        // The Keychain store is one instance behind both interfaces.
        assertTrue(koin.get<SecretStore>() === koin.get<LastOpenedListStore>())
    }

    private fun resolve(koin: org.koin.core.Koin, definition: BeanDefinition<*>): Any? =
        koin.get<Any>(clazz = definition.primaryType, qualifier = definition.qualifier)

    private fun startApp(): KoinApplication = koinApplication {
        val files = AppFiles("${NSTemporaryDirectory()}tuppu-test-${NSUUID().UUIDString}")
        modules(listOf(iosPlatformModule(files)) + commonModules)
    }.also { app = it }
}
