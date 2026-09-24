package org.p23q.shoppinglist

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.LegacySessionSource
import org.p23q.shoppinglist.data.crash.CrashHandler
import org.p23q.shoppinglist.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class ShoppingListApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var crashHandler: CrashHandler

    @Inject lateinit var appForegroundState: AppForegroundState

    @Inject lateinit var syncEngine: SyncEngine

    @Inject lateinit var accountRegistry: AccountRegistry

    @Inject lateinit var legacySession: LegacySessionSource

    /** Outlives every screen, for the one-shot start-up seed below — never used for per-screen work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
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
