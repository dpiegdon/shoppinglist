package org.p23q.shoppinglist.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.api.HttpEngineFactory
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.InviteNotifier
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.KeystoreSecretStore
import org.p23q.shoppinglist.data.LastServerAddress
import org.p23q.shoppinglist.data.LegacySessionSource
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.data.StoredLegacySession
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.api.OkHttpEngineFactory
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.data.crash.CrashHandler
import org.p23q.shoppinglist.data.crash.CrashLog
import org.p23q.shoppinglist.data.crash.crashLogWriterIn
import org.p23q.shoppinglist.data.db.buildAppDb
import org.p23q.shoppinglist.data.notify.CollaboratorChangeNotificationPoster
import org.p23q.shoppinglist.data.notify.InviteNotificationPoster
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.sync.BackgroundInviteCheck
import org.p23q.shoppinglist.data.sync.SyncScheduler
import org.p23q.shoppinglist.data.sync.TuppuSyncWorker
import org.p23q.shoppinglist.data.update.UpdateChecker
import org.p23q.shoppinglist.data.update.UpdatePrefsStore

/**
 * The Android implementations: everything built on a [Context] (the database file, the DataStore
 * files, the Keystore, WorkManager, notifications), and the Android-only services built on them.
 * An iOS app supplies its own module in place of this one; [commonModules] (in :shared) only names
 * what this provides.
 *
 * `single` is one instance per process, `factory` a new one for every injection.
 */
val appPlatformModule: Module = module {
    single { KeystoreSecretStore(androidContext()) } binds arrayOf(SecretStore::class, LastOpenedListStore::class)
    single { StoredLegacySession(get(), get()) } bind LegacySessionSource::class
    single { ServerConfig(preferencesDataStore(androidContext(), "server_config")) } bind LastServerAddress::class
    single<DeviceIdProvider> {
        val serverConfig = get<ServerConfig>()
        DeviceIdProvider { serverConfig.deviceId() }
    }
    single { buildAppDb(androidContext(), get()) }
    single { OkHttpEngineFactory() } bind HttpEngineFactory::class
    single(DEVICE_NAME) { "${Build.MANUFACTURER} ${Build.MODEL}" }

    single { ThemePreferenceStore(preferencesDataStore(androidContext(), "theme_preference")) }
    single { LocalePreferenceStore(preferencesDataStore(androidContext(), "locale_preference")) }
    single { ShowCheckedStore(preferencesDataStore(androidContext(), "show_checked")) }
    single { UpdatePrefsStore(preferencesDataStore(androidContext(), "update_prefs")) }
    single { NotificationPrefsStore(preferencesDataStore(androidContext(), "notification_prefs")) }

    single { crashLogWriterIn(androidContext()) } bind CrashLog::class
    single { CrashHandler(get()) }
    single { AppForegroundState() }
    single { UpdateChecker(get(), get(), get(), appVersion = BuildConfig.VERSION_NAME) }

    single { SyncScheduler(androidContext()) } bind SyncTrigger::class
    single { CollaboratorChangeNotificationPoster(androidContext(), get(), get(), get(), get()) } bind
        CollaboratorChangeNotifier::class
    single { InviteNotificationPoster(androidContext(), get(), get(), get(), get()) } bind InviteNotifier::class
    factory { BackgroundInviteCheck(get(), get()) }
    // Built by ShoppingListApp's KoinWorkerFactory, which passes the WorkerParameters.
    worker { params -> TuppuSyncWorker(androidContext(), params.get(), get(), get(), get()) }
}

/** Everything the app starts Koin with. */
val appModules: List<Module> = listOf(appPlatformModule) + commonModules

/** One DataStore file under the app's files; each is created once, by the one store that owns it. */
private fun preferencesDataStore(context: Context, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }
