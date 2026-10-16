package org.p23q.shoppinglist

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.LegacySessionSource
import org.p23q.shoppinglist.data.crash.CrashHandler
import org.p23q.shoppinglist.data.sync.SyncScheduler
import org.p23q.shoppinglist.di.appModules

/**
 * Starts Koin (T-350) with the app's modules (di/) before anything below resolves from it. The
 * tests run under RobolectricTestApp instead, which starts neither Koin nor WorkManager.
 */
class ShoppingListApp : Application(), Configuration.Provider {

    private val syncScheduler: SyncScheduler by inject()

    private val crashHandler: CrashHandler by inject()

    private val appForegroundState: AppForegroundState by inject()

    private val syncEngine: SyncEngine by inject()

    private val accountRegistry: AccountRegistry by inject()

    private val legacySession: LegacySessionSource by inject()

    /** Outlives every screen, for the one-shot start-up seed below — never used for per-screen work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(KoinWorkerFactory()).build()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ShoppingListApp)
            modules(appModules)
        }
        crashHandler.install()
        appScope.launch {
            // Opening the accounts opens the database, which runs the schema-9 migration on an
            // upgraded install; only after that may the single-session keys it read go (T-291).
            accountRegistry.load()
            legacySession.discard()
            // Every scheduled sync below needs connectivity, so a cold start offline would otherwise
            // leave SyncStatus at its initial zeros — no pending count, no attention banner — until
            // one finally runs (T-265). This reads the database only, no network.
            syncEngine.seedStatus()
        }
        syncScheduler.schedulePeriodic()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                // Notes: sync on app foreground, in addition to the periodic/after-edit triggers.
                override fun onStart(owner: LifecycleOwner) {
                    appForegroundState.isForeground = true
                    syncScheduler.scheduleImmediate()
                }

                // Tracked so collaborator-change notifications stay silent while the app is on
                // screen — the change is already visible there (T-65).
                override fun onStop(owner: LifecycleOwner) {
                    appForegroundState.isForeground = false
                }
            },
        )
    }
}
