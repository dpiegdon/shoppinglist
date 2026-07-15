package org.p23q.shoppinglist

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.crash.CrashHandler
import org.p23q.shoppinglist.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class ShoppingListApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var crashHandler: CrashHandler

    @Inject lateinit var appForegroundState: AppForegroundState

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        crashHandler.install()
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
