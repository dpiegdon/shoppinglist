package org.p23q.shoppinglist.di

import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.AuthRepositoryImpl
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.api.AppJson
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.InviteChecker
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.ListAccounts
import org.p23q.shoppinglist.data.LocalArea
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.ui.DrawerViewModel
import org.p23q.shoppinglist.ui.ListTitleViewModel
import org.p23q.shoppinglist.ui.LocaleViewModel
import org.p23q.shoppinglist.ui.RootViewModel
import org.p23q.shoppinglist.ui.SyncStatusViewModel
import org.p23q.shoppinglist.ui.accounts.AccountViewModel
import org.p23q.shoppinglist.ui.accounts.AccountsViewModel
import org.p23q.shoppinglist.ui.admin.AdminViewModel
import org.p23q.shoppinglist.ui.expense.ExpenseFormViewModel
import org.p23q.shoppinglist.ui.expense.ExpenseListViewModel
import org.p23q.shoppinglist.ui.item.ItemFormViewModel
import org.p23q.shoppinglist.ui.list.ListViewModel
import org.p23q.shoppinglist.ui.listprops.ListPropsViewModel
import org.p23q.shoppinglist.ui.login.KnownAccounts
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.p23q.shoppinglist.ui.overview.OverviewViewModel
import org.p23q.shoppinglist.ui.redeem.RedeemViewModel
import org.p23q.shoppinglist.ui.registry.RegistryViewModel
import org.p23q.shoppinglist.ui.settings.SettingsViewModel
import org.p23q.shoppinglist.ui.update.UpdateViewModel

/*
 * The wiring every platform shares: :core's classes, the app's seams over them and the view
 * models. What these need from the platform (the database, the secrets, the HTTP engine, the sync
 * trigger, the notifiers, the preference stores with their files, the crash log, the update
 * checker with the app's version, [DEVICE_NAME]) comes from the platform's own module: on Android
 * `appPlatformModule` in :app.
 *
 * `single` is one instance per process, `factory` a new one for every injection, and `viewModel`
 * one per ViewModelStoreOwner: the navigation back-stack entry, or the Activity outside the NavHost.
 */

/** The label the server shows for this device's sessions, e.g. "Google Pixel 8". */
val DEVICE_NAME = named("deviceName")

/** :core's classes, and the app's thin seams over them. */
val coreModule: Module = module {
    single<Json> { AppJson }
    single { SyncStatus() }
    single { AccountRegistry(get()) }
    single { AccountSessions(get(), get(), get(), get(), get()) }
    single { AuthRepositoryImpl(get(), get(), get(), get(), get(), deviceName = get(DEVICE_NAME)) } bind
        AuthRepository::class
    factory { get<AppDb>().listDao() }
    factory { get<AppDb>().itemDao() }
    factory { ListsRepo(get(), get(), get()) }
    factory { ItemsRepo(get(), get(), get()) }
    factory { SyncEngine(get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { EngineSyncer(get()) } bind Syncer::class
    factory { InviteChecker(get(), get(), get(), get()) }

    single { PendingInviteHolder() }
    factory { ListAccounts(get(), get(), get()) }
    factory<LocalArea> {
        val registry = get<AccountRegistry>()
        LocalArea { registry.addLocal() != null }
    }
    factory<KnownAccounts> {
        val registry = get<AccountRegistry>()
        KnownAccounts { registry.snapshot() }
    }
}

/**
 * Every screen's view model. A [androidx.lifecycle.SavedStateHandle] parameter is the owner's: on
 * a back-stack entry it holds the route's arguments.
 */
val viewModelModule: Module = module {
    viewModelOf(::RootViewModel)
    viewModelOf(::LocaleViewModel)
    viewModelOf(::UpdateViewModel)
    viewModelOf(::DrawerViewModel)
    viewModelOf(::SyncStatusViewModel)
    viewModelOf(::ListTitleViewModel)
    viewModelOf(::OverviewViewModel)
    viewModelOf(::ListViewModel)
    viewModelOf(::ItemFormViewModel)
    viewModelOf(::ExpenseListViewModel)
    viewModelOf(::ExpenseFormViewModel)
    viewModelOf(::RegistryViewModel)
    viewModelOf(::ListPropsViewModel)
    viewModelOf(::AccountsViewModel)
    viewModelOf(::AccountViewModel)
    viewModelOf(::RedeemViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel {
        LoginViewModel(
            authRepository = get(),
            knownAccounts = get(),
            serverConfig = get(),
            pendingInviteHolder = get(),
            syncTrigger = get(),
            savedStateHandle = get(),
            localArea = get(),
        )
    }
    viewModel { AdminViewModel(savedStateHandle = get(), sessions = get(), registry = get()) }
}

/** What every platform's app starts Koin with, beside its own platform module. */
val commonModules: List<Module> = listOf(coreModule, viewModelModule)

/**
 * Screens depend on the [Syncer] seam for pull-to-refresh; [SyncEngine] is the impl (T-36). A thin
 * adapter so SyncEngine keeps its own default-arg public API unchanged. A list this device does not
 * hold yet is asked of the account that has just joined it.
 */
class EngineSyncer(private val engine: SyncEngine) : Syncer {
    override suspend fun syncNow(fullLists: List<String>): SyncResult = engine.syncNow(fullLists)

    override suspend fun syncJoined(accountId: String, serverListId: String): SyncResult =
        engine.syncNow(listOf(serverListId), fullListsAccountId = accountId)
}
