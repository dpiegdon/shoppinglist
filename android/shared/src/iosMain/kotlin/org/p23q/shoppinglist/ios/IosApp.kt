package org.p23q.shoppinglist.ios

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.crash.IosCrashLog
import org.p23q.shoppinglist.di.commonModules
import org.p23q.shoppinglist.di.iosPlatformModule
import org.p23q.shoppinglist.ui.TuppuApp
import org.p23q.shoppinglist.ui.coldStartDestination
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIViewController

/*
 * What the Swift shell (../../../../../../../../ios) calls: [startIosApp] once, from the app
 * delegate, then [MainViewController] for the window. Together they are Android's
 * ShoppingListApp.onCreate and MainActivity.onCreate.
 */

/**
 * Starts Koin with the iOS module beside [commonModules], installs the crash log, loads the
 * accounts (which opens the database), seeds the sync status from it (T-265), and syncs: now, and
 * again whenever the app comes to the foreground.
 */
fun startIosApp() {
    val koin = startKoin { modules(listOf(iosPlatformModule()) + commonModules) }.koin
    koin.get<IosCrashLog>().install()
    // Before the first screen, as MainActivity does: one read of a small table, after which every
    // account read in the view models is synchronous.
    runBlocking(Dispatchers.Default) {
        koin.get<AccountRegistry>().load()
        koin.get<SyncEngine>().seedStatus()
    }
    val syncTrigger = koin.get<SyncTrigger>()
    syncTrigger.scheduleImmediate()
    NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidBecomeActiveNotification, `object` = null, queue = NSOperationQueue.mainQueue,
    ) { _ -> syncTrigger.scheduleImmediate() }
}

/** The whole app in one view controller: [TuppuApp] from where a cold start lands. */
@Suppress("FunctionName")
fun MainViewController(): UIViewController {
    val koin = KoinPlatform.getKoin()
    val startDestination = coldStartDestination(
        accounts = koin.get<AccountRegistry>().snapshot(),
        notifiedListId = null,
        lastOpenedListId = koin.get<LastOpenedListStore>().lastOpenedListId,
    )
    return ComposeUIViewController { TuppuApp(startDestination = startDestination) }
}
