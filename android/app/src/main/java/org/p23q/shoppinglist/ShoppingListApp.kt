package org.p23q.shoppinglist

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.p23q.shoppinglist.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class ShoppingListApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.schedulePeriodic()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                // Notes: sync on app foreground, in addition to the periodic/after-edit triggers.
                override fun onStart(owner: LifecycleOwner) {
                    syncScheduler.scheduleImmediate()
                }
            },
        )
    }
}
