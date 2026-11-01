package org.p23q.shoppinglist.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.api.DarwinEngineFactory
import org.p23q.shoppinglist.core.api.HttpEngineFactory
import org.p23q.shoppinglist.core.db.buildAppDb
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.InviteNotifier
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.AppFiles
import org.p23q.shoppinglist.data.KeychainSecretStore
import org.p23q.shoppinglist.data.LastServerAddress
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLog
import org.p23q.shoppinglist.data.crash.IosCrashLog
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.notify.SilentCollaboratorChangeNotifier
import org.p23q.shoppinglist.data.notify.SilentInviteNotifier
import org.p23q.shoppinglist.data.sync.InAppSyncTrigger
import org.p23q.shoppinglist.data.update.UpdateChecker
import org.p23q.shoppinglist.data.update.UpdatePrefsStore
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/**
 * The iOS implementations of what [commonModules] needs from the platform, as `appPlatformModule`
 * in :app is Android's: the files (database, preference stores, crash log) under [AppFiles], the
 * tokens on the Keychain, NSURLSession for the API, and the syncs an open app runs by itself. The
 * notifiers stay silent until step 4 (T-354). [files] is the app's directory; a test passes a
 * temporary one.
 *
 * `single` is one instance per process, `factory` a new one for every injection.
 */
fun iosPlatformModule(files: AppFiles = AppFiles.inApplicationSupport()): Module = module {
    single { files }
    single { KeychainSecretStore() } binds arrayOf(SecretStore::class, LastOpenedListStore::class)
    single { ServerConfig(files.preferencesDataStore("server_config")) } bind LastServerAddress::class
    single<DeviceIdProvider> {
        val serverConfig = get<ServerConfig>()
        DeviceIdProvider { serverConfig.deviceId() }
    }
    single { buildAppDb(files.databasePath) }
    single { DarwinEngineFactory() } bind HttpEngineFactory::class
    // As Android's "<manufacturer> <model>": the model, not the user's name for the phone, which
    // iOS no longer hands out anyway.
    single(DEVICE_NAME) { "Apple ${UIDevice.currentDevice.model}" }

    single { ThemePreferenceStore(files.preferencesDataStore("theme_preference")) }
    single { LocalePreferenceStore(files.preferencesDataStore("locale_preference")) }
    single { ShowCheckedStore(files.preferencesDataStore("show_checked")) }
    single { UpdatePrefsStore(files.preferencesDataStore("update_prefs")) }
    single { NotificationPrefsStore(files.preferencesDataStore("notification_prefs")) }

    single { IosCrashLog(files.crashLogPath) } bind CrashLog::class
    single { UpdateChecker(get(), get(), get(), appVersion = appVersion()) }

    single {
        InAppSyncTrigger(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), syncer = { get() })
    } bind SyncTrigger::class
    single { SilentCollaboratorChangeNotifier() } bind CollaboratorChangeNotifier::class
    single { SilentInviteNotifier() } bind InviteNotifier::class
}

/** The bundle's short version ("4.0.0"); a test binary has none. */
private fun appVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"
