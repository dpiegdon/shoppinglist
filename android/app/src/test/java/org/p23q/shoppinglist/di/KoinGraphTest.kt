package org.p23q.shoppinglist.di

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.FakeSecretStore
import org.p23q.shoppinglist.data.KeystoreSecretStore
import org.p23q.shoppinglist.data.LegacySession
import org.p23q.shoppinglist.data.LegacySessionSource
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.StoredLegacySession
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.reflect.full.isSubclassOf

/**
 * The Koin graph the app starts with (T-350) is complete: every class it builds finds what its
 * constructor asks for, and every definition actually resolves.
 */
@RunWith(RobolectricTestRunner::class)
class KoinGraphTest {

    private var app: KoinApplication? = null

    @After
    fun tearDown() {
        app?.close()
    }

    /**
     * Koin's static check: every public constructor of every defined class, view models and the
     * worker included, against the types the modules define. The extra types are the ones Koin
     * supplies itself (the Context, a view model's SavedStateHandle, a worker's parameters) or
     * that a definition builds in place (each preference store's DataStore, the crash log's file).
     */
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `every constructor finds its dependencies`() {
        module { includes(appModules) }.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                SavedStateHandle::class,
                WorkerParameters::class,
                DataStore::class,
                File::class,
            ),
        )
    }

    /**
     * Resolves every definition but the view models and the worker, which need a ViewModelStore
     * or WorkerParameters and are covered by the constructor check above. That also runs each
     * definition's own code, which the constructor check cannot see: an interface built from a
     * lambda, a qualifier. Only the Keystore-backed stores are replaced, as Robolectric has no
     * Android Keystore.
     */
    @OptIn(KoinInternalApi::class)
    @Test
    fun `every definition resolves`() {
        val koin = startApp().koin
        val skipped = setOf(KeystoreSecretStore::class, StoredLegacySession::class)
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }.distinct()
            .filterNot { it.primaryType in skipped }
            .filterNot { it.primaryType.isSubclassOf(ViewModel::class) || it.primaryType.isSubclassOf(ListenableWorker::class) }
        assertTrue("the definitions are found", definitions.size > 30)

        for (definition in definitions) {
            val instance = resolve(koin, definition)
            assertNotNull("$definition", instance)
        }
    }

    @Test
    fun `singles are one per process and factories new each time`() {
        val koin = startApp().koin

        assertTrue(koin.get<SyncStatus>() === koin.get<SyncStatus>())
        assertTrue(koin.get<PendingInviteHolder>() === koin.get<PendingInviteHolder>())
        assertTrue(koin.get<Syncer>() !== koin.get<Syncer>())
        // A store bound to two interfaces is one instance behind both.
        assertEquals(koin.get<SecretStore>(), koin.get<LastOpenedListStore>())
    }

    private fun resolve(koin: org.koin.core.Koin, definition: BeanDefinition<*>): Any? =
        koin.get<Any>(definition.primaryType, definition.qualifier)

    private fun startApp(): KoinApplication = koinApplication {
        allowOverride(true)
        androidContext(ApplicationProvider.getApplicationContext())
        modules(appModules + keystoreFree)
    }.also { app = it }

    private val keystoreFree = module {
        single { FakeSecretStore() } binds arrayOf(SecretStore::class, LastOpenedListStore::class)
        single<LegacySessionSource> {
            object : LegacySessionSource {
                override fun read() = LegacySession()

                override fun adoptToken(localAccountId: String) = Unit

                override suspend fun discard() = Unit
            }
        }
    }
}
