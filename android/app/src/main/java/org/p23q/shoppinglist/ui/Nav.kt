package org.p23q.shoppinglist.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.ui.about.AboutScreen
import org.p23q.shoppinglist.ui.admin.AdminScreen
import org.p23q.shoppinglist.ui.admin.AdminViewModel
import org.p23q.shoppinglist.ui.expense.ExpenseDialog
import org.p23q.shoppinglist.ui.expense.ExpenseListScreen
import org.p23q.shoppinglist.ui.expense.ExpensePrefill
import org.p23q.shoppinglist.ui.item.AddItemDialog
import org.p23q.shoppinglist.ui.item.EditItemDialog
import org.p23q.shoppinglist.ui.list.ListScreen
import org.p23q.shoppinglist.ui.listprops.ListPropsScreen
import org.p23q.shoppinglist.ui.login.LoginScreen
import org.p23q.shoppinglist.ui.login.LoginMode
import org.p23q.shoppinglist.ui.accounts.AccountGone
import org.p23q.shoppinglist.ui.accounts.AccountScreen
import org.p23q.shoppinglist.ui.accounts.AccountsScreen
import org.p23q.shoppinglist.ui.overview.OverviewScreen
import org.p23q.shoppinglist.ui.redeem.RedeemDialog
import org.p23q.shoppinglist.ui.redeem.RedeemScreen
import org.p23q.shoppinglist.ui.registry.RegistryScreen
import org.p23q.shoppinglist.ui.settings.SettingsScreen
import org.p23q.shoppinglist.ui.update.UpdateRequiredScreen
import org.p23q.shoppinglist.ui.update.UpdateStatus
import org.p23q.shoppinglist.ui.update.UpdateViewModel

/** Route patterns and builders for [ShoppingListNavHost]. */
object Routes {
    /** The start screen; also matches [LOGIN_PATTERN] with every argument left out. */
    const val LOGIN = "login"
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val ACCOUNTS = "accounts"

    const val LOGIN_MODE_ARG = "mode"
    const val ACCOUNT_ID_ARG = "accountId"
    const val SERVER_URL_ARG = "serverUrl"

    /** The login form; [LoginMode] says what for. All three arguments are optional. */
    const val LOGIN_PATTERN = "login?$LOGIN_MODE_ARG={$LOGIN_MODE_ARG}&$ACCOUNT_ID_ARG={$ACCOUNT_ID_ARG}&$SERVER_URL_ARG={$SERVER_URL_ARG}"
    const val ACCOUNT_PATTERN = "account/{$ACCOUNT_ID_ARG}"
    const val ADMIN_PATTERN = "admin/{$ACCOUNT_ID_ARG}"

    const val LIST_ID_ARG = "listId"
    const val LIST_PATTERN = "list/{$LIST_ID_ARG}"
    const val REGISTRY_PATTERN = "registry/{$LIST_ID_ARG}"
    const val LIST_PROPS_PATTERN = "listProps/{$LIST_ID_ARG}"

    const val TOKEN_ARG = "token"
    /** The invite link a token came as, which names its server (T-292); optional. */
    const val LINK_ARG = "link"
    /** The account to redeem into, when already settled; optional. */
    const val ACCOUNT_ARG = "accountId"
    const val REDEEM_PATTERN = "redeem/{$TOKEN_ARG}?$LINK_ARG={$LINK_ARG}&$ACCOUNT_ARG={$ACCOUNT_ARG}"

    fun list(listId: String) = "list/$listId"
    fun registry(listId: String) = "registry/$listId"
    fun listProps(listId: String) = "listProps/$listId"
    fun redeem(token: String, link: String? = null, accountId: String? = null) = buildString {
        append("redeem/").append(routeArg(token))
        val args = listOfNotNull(link?.let { "$LINK_ARG=" + routeArg(it) }, accountId?.let { "$ACCOUNT_ARG=" + routeArg(it) })
        if (args.isNotEmpty()) append('?').append(args.joinToString("&"))
    }
    fun account(accountId: String) = "account/$accountId"
    fun admin(accountId: String) = "admin/$accountId"

    /**
     * The login form in [mode]: for [LoginMode.RESIGNIN] the local id of the account signing in
     * again, for [LoginMode.ADD] optionally the server URL to prefill.
     */
    fun login(mode: LoginMode, accountId: String? = null, serverUrl: String? = null): String = buildString {
        append("login?$LOGIN_MODE_ARG=${mode.arg}")
        accountId?.let { append("&$ACCOUNT_ID_ARG=${routeArg(it)}") }
        serverUrl?.let { append("&$SERVER_URL_ARG=${routeArg(it)}") }
    }
}

/**
 * [value] percent-encoded for a route argument: every byte outside the URI's unreserved set, so a
 * server URL's ':' and '/' survive as one argument. Plain Kotlin rather than android.net.Uri, so
 * the routes can be built in a plain JVM test.
 */
internal fun routeArg(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
            append(c)
        } else {
            append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}

/**
 * The current list's live name for the top-bar title on list/registry/props, falling back to a
 * mode label while the name is momentarily loading. Scoped to the current nav back-stack entry, so
 * it reflects renames (local or synced) without recreating the screen (T-34).
 */
@Composable
private fun liveListTitle(fallback: String): String {
    val viewModel: ListTitleViewModel = hiltViewModel()
    val name by viewModel.name.collectAsStateWithLifecycle()
    return name.ifBlank { fallback }
}

/** The list's account under its name, with several accounts (T-292); null with one. */
@Composable
private fun liveListSubtitle(): String? {
    val viewModel: ListTitleViewModel = hiltViewModel()
    val account by viewModel.subtitleAccount.collectAsStateWithLifecycle()
    return account?.let { accountLineText(it) }
}

/**
 * Where an already-authenticated session should land: the last-opened list if one is remembered
 * (Notes: "on login, open the list the user last had open"), otherwise the overview. Shared by the
 * cold-start resume in [org.p23q.shoppinglist.MainActivity] and the post-login navigation in
 * [org.p23q.shoppinglist.ui.login.LoginViewModel].
 */
fun authedStartDestination(lastOpenedListId: String?): String =
    lastOpenedListId?.let { Routes.list(it) } ?: Routes.OVERVIEW

/**
 * Where a cold start lands. The start screen only while this phone holds no account at all: an
 * account the server has signed out keeps its lists, which stay usable, and the overview offers
 * the sign-in; the local area alone is a phone used without an account (T-293). Otherwise the list
 * a notification tap names ([notifiedListId]), the overview for an invite notification's tap
 * ([openOverview]), else as [authedStartDestination].
 */
fun coldStartDestination(
    accounts: List<AccountEntity>,
    notifiedListId: String?,
    lastOpenedListId: String?,
    openOverview: Boolean = false,
): String =
    when {
        accounts.isEmpty() -> Routes.LOGIN_PATTERN
        notifiedListId != null -> Routes.list(notifiedListId)
        openOverview -> Routes.OVERVIEW
        else -> authedStartDestination(lastOpenedListId)
    }

/**
 * Where a successful sign-in on a form in [mode] goes: to [destination], or back where the form
 * was opened from when there is none. The start screen leaves nothing behind it (T-300): opened
 * for an invite it may be the second login entry on the stack, over the first one's.
 */
internal fun NavHostController.afterLogin(mode: LoginMode, destination: String?) {
    when {
        destination == null -> popBackStack()
        mode == LoginMode.START -> navigate(destination) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
        else -> navigate(destination) {
            popUpTo(Routes.LOGIN_PATTERN) { inclusive = true }
        }
    }
}

/** Where the Account screen goes once its account has left this phone. */
internal fun NavHostController.afterAccountGone(gone: AccountGone) {
    when (gone) {
        AccountGone.TO_ACCOUNTS -> navigate(Routes.ACCOUNTS) {
            popUpTo(Routes.ACCOUNTS) { inclusive = true }
            launchSingleTop = true
        }
        // The last account went, the local area too: nothing is left to show but the start, and the route is
        // built, not the pattern, whose placeholders would fill the form's fields (T-300).
        AccountGone.TO_START -> navigate(Routes.login(LoginMode.START)) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/**
 * Where List properties goes once its list has left this phone (deleted, or left): the overview,
 * with nothing behind it. Not `popUpTo(OVERVIEW)`: a cold start on the last opened list, or a
 * sign-in on the start screen, leaves no overview on the stack to pop to (T-302).
 */
internal fun NavHostController.afterListLeft() {
    navigate(Routes.OVERVIEW) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Where List properties goes after Duplicate: the copy, with the overview behind it, whatever
 * the stack held before, so Back from the copy never lands on the source list's properties (T-302).
 */
internal fun NavHostController.afterListDuplicated(newListId: String) {
    navigate(Routes.OVERVIEW) {
        popUpTo(graph.id) { inclusive = true }
    }
    navigate(Routes.list(newListId))
}

/**
 * Go back to [listId] from one of its sub-screens — properties or the registry.
 *
 * Both of those show the list's own name in the top bar (see [liveListTitle]), so tapping that
 * name reads as "back to this list", mirroring the web client's "← <list name>" link above the
 * heading. The list screen already uses the same gesture to go up to the overview.
 *
 * `popUpTo` WITHOUT `inclusive` is the point: it returns to the list entry already on the back
 * stack instead of pushing a second copy, so this behaves as Back rather than as forward
 * navigation that happens to land on the list. `launchSingleTop` then reuses that entry rather
 * than recreating it.
 */
private fun NavHostController.backToList(listId: String) {
    navigate(Routes.list(listId)) {
        popUpTo(Routes.list(listId))
        launchSingleTop = true
    }
}

@Composable
fun ShoppingListNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.LOGIN_PATTERN,
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // Obtained here, not inside each screen: Nav is already inside the Hilt graph, and keeping
    // the screens Hilt-free is what lets them be rendered directly in unit tests (T-127).
    val localeViewModel: LocaleViewModel = hiltViewModel()
    val selectedLocale by localeViewModel.locale.collectAsStateWithLifecycle()

    // Update check (T-135). ON_START rather than a one-shot LaunchedEffect: a phone that stays on
    // this app for days would otherwise never notice a release. UpdateChecker owns the rate limit,
    // so firing this on every foreground costs nothing.
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val availableUpdate by updateViewModel.availableUpdate.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { updateViewModel.check() }

    // Too old for this server (T-240). Rendered INSTEAD of the NavHost, not over it: every request
    // is being refused, so whatever is behind it could only show stale data and a sync that never
    // succeeds. The state is only ever set, never cleared — a successful install restarts the app.
    // Never while the local area is here, whose lists need no server (T-293).
    val updateRequired by rootViewModel.updateRequired.collectAsStateWithLifecycle()
    if (updateRequired) {
        val updateStatus by updateViewModel.status.collectAsStateWithLifecycle()
        val context = LocalContext.current
        UpdateRequiredScreen(
            status = updateStatus,
            update = availableUpdate,
            // Ignores the auto-check switch and the twelve-hour interval: this one is not optional.
            onOpened = { updateViewModel.checkRequired() },
            onRetry = { updateViewModel.checkRequired() },
            onDownload = { update -> openDownload(context, update.downloadUrl) },
        )
        return
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Screens swap on the next frame instead of crossfading. NavHost's defaults are
        // fadeIn/fadeOut(tween(700)) — over twice Material's duration for a transition of this
        // kind, and long enough to read as lag rather than as polish once the list title became a
        // one-tap jump back to the overview. All four are set explicitly: popEnter/popExit only
        // fall back to enter/exit while they are left unspecified, and a fade reappearing on Back
        // alone would be the subtler bug.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(
            route = Routes.LOGIN_PATTERN,
            arguments = listOf(Routes.LOGIN_MODE_ARG, Routes.ACCOUNT_ID_ARG, Routes.SERVER_URL_ARG).map { name ->
                navArgument(name) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            },
        ) { backStackEntry ->
            val context = LocalContext.current
            val mode = LoginMode.fromArg(backStackEntry.arguments?.getString(Routes.LOGIN_MODE_ARG))
            val form = @Composable {
                LoginScreen(
                    selectedLocale = selectedLocale,
                    onSelectLocale = localeViewModel::setLocale,
                    onDownload = { url -> openDownload(context, url) },
                    onLoginSuccess = { destination -> navController.afterLogin(mode, destination) },
                )
            }
            if (mode == LoginMode.START) {
                form()
            } else {
                BackScaffold(
                    title = stringResource(if (mode == LoginMode.ADD) R.string.accounts_add else R.string.login_resignin_title),
                    onBack = { navController.popBackStack() },
                ) { form() }
            }
        }
        composable(Routes.ACCOUNTS) {
            AppDrawerScaffold(navController = navController, title = stringResource(R.string.nav_accounts)) {
                AccountsScreen(
                    onAddAccount = { navController.navigate(Routes.login(LoginMode.ADD)) },
                    onOpenAccount = { id -> navController.navigate(Routes.account(id)) },
                    onSignIn = { id -> navController.navigate(Routes.login(LoginMode.RESIGNIN, accountId = id)) },
                    onCheckForUpdate = { updateViewModel.checkForOutdated() },
                )
            }
        }
        composable(Routes.ACCOUNT_PATTERN) { backStackEntry ->
            val accountId = checkNotNull(backStackEntry.arguments?.getString(Routes.ACCOUNT_ID_ARG))
            AppDrawerScaffold(
                navController = navController,
                title = stringResource(R.string.settings_account),
                onTitleClick = { navController.popBackStack(Routes.ACCOUNTS, inclusive = false) },
            ) {
                AccountScreen(
                    onGone = { gone -> navController.afterAccountGone(gone) },
                    onSignIn = { navController.navigate(Routes.login(LoginMode.RESIGNIN, accountId = accountId)) },
                )
            }
        }
        composable(Routes.OVERVIEW) {
            AppDrawerScaffold(
                navController = navController,
                title = stringResource(R.string.nav_overview),
                actions = {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_logo),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp).size(28.dp),
                    )
                },
            ) {
                OverviewScreen(
                    onOpenList = { listId -> navController.navigate(Routes.list(listId)) },
                    // A signed-out account's banner: sign that account in again (T-292).
                    onSignIn = { accountId ->
                        navController.navigate(Routes.login(LoginMode.RESIGNIN, accountId = accountId))
                    },
                    // An outdated account's banner (T-304).
                    onCheckForUpdate = { updateViewModel.checkForOutdated() },
                )
            }
        }
        composable(Routes.LIST_PATTERN) { backStackEntry ->
            val listId = checkNotNull(backStackEntry.arguments?.getString(Routes.LIST_ID_ARG))
            AppDrawerScaffold(
                navController = navController,
                title = liveListTitle(stringResource(R.string.nav_list)),
                subtitle = liveListSubtitle(),
                // Tapping the open list's title jumps back to the overview to pick another list.
                onTitleClick = {
                    navController.navigate(Routes.OVERVIEW) {
                        popUpTo(Routes.OVERVIEW)
                        launchSingleTop = true
                    }
                },
            ) {
                var isAddDialogOpen by rememberSaveable { mutableStateOf(false) }
                var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }

                // An expenses list shares almost nothing with a shopping list on screen, so it
                // gets its own screen rather than a branch inside ListScreen (T-154). Nothing is
                // rendered until the kind is known: guessing would flash the wrong screen.
                val listKindViewModel: ListTitleViewModel = hiltViewModel()
                val kind by listKindViewModel.kind.collectAsStateWithLifecycle()
                // However the list was reached, it is the last one opened (T-300).
                LaunchedEffect(listId) { listKindViewModel.opened() }

                when {
                    kind == null -> Unit
                    ListKind.isExpenses(kind) -> {
                        // Plain remember, not rememberSaveable: the prefill is not Parcelable, and losing an
                        // unsaved settlement form to process death costs one tap to reopen.
                        var reimbursing by remember { mutableStateOf<ExpensePrefill?>(null) }
                        ExpenseListScreen(
                            onAddExpense = { isAddDialogOpen = true },
                            onEditExpense = { itemId -> editingItemId = itemId },
                            onOpenListProps = { navController.navigate(Routes.listProps(listId)) },
                            onReimburse = { reimbursing = it },
                        )
                        if (isAddDialogOpen || editingItemId != null || reimbursing != null) {
                            ExpenseDialog(
                                listId = listId,
                                itemId = editingItemId,
                                prefill = reimbursing,
                                onDismiss = {
                                    isAddDialogOpen = false
                                    editingItemId = null
                                    reimbursing = null
                                },
                            )
                        }
                    }
                    else -> {
                        ListScreen(
                            onAddItem = { isAddDialogOpen = true },
                            onEditItem = { itemId -> editingItemId = itemId },
                            onOpenRegistry = { navController.navigate(Routes.registry(listId)) },
                            onOpenListProps = { navController.navigate(Routes.listProps(listId)) },
                        )

                        if (isAddDialogOpen) {
                            AddItemDialog(listId = listId, onDismiss = { isAddDialogOpen = false })
                        }
                        editingItemId?.let { itemId ->
                            EditItemDialog(itemId = itemId, onDismiss = { editingItemId = null })
                        }
                    }
                }
            }
        }
        composable(Routes.REGISTRY_PATTERN) { backStackEntry ->
            val listId = checkNotNull(backStackEntry.arguments?.getString(Routes.LIST_ID_ARG))
            AppDrawerScaffold(
                navController = navController,
                title = liveListTitle(stringResource(R.string.nav_registry)),
                onTitleClick = { navController.backToList(listId) },
            ) {
                var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }

                RegistryScreen(onEditItem = { itemId -> editingItemId = itemId })

                editingItemId?.let { itemId ->
                    EditItemDialog(itemId = itemId, onDismiss = { editingItemId = null })
                }
            }
        }
        composable(Routes.LIST_PROPS_PATTERN) { backStackEntry ->
            val listId = checkNotNull(backStackEntry.arguments?.getString(Routes.LIST_ID_ARG))
            AppDrawerScaffold(
                navController = navController,
                title = liveListTitle(stringResource(R.string.nav_list_properties)),
                onTitleClick = { navController.backToList(listId) },
            ) {
                ListPropsScreen(
                    onLeft = { navController.afterListLeft() },
                    onDuplicated = { newListId -> navController.afterListDuplicated(newListId) },
                )
            }
        }
        composable(
            route = Routes.REDEEM_PATTERN,
            arguments = listOf(
                navArgument(Routes.TOKEN_ARG) { type = NavType.StringType },
                navArgument(Routes.LINK_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(Routes.ACCOUNT_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // The manifest's intent-filter (any https host, /invite/ at the root or under a mount
            // path) gets the OS to launch this Activity; these deep links route the resulting
            // Intent to this destination and extract the token. The whole link, which names the
            // server and so the account (T-292), rides along in the deep-link Intent.
            deepLinks = INVITE_LINK_PATTERNS.map { pattern -> navDeepLink { uriPattern = pattern } },
        ) { backStackEntry ->
            val arguments = backStackEntry.arguments
            val token = checkNotNull(arguments?.getString(Routes.TOKEN_ARG))
            RedeemScreen(
                token = token,
                link = inviteLinkOf(arguments),
                accountId = arguments?.getString(Routes.ACCOUNT_ARG),
                onRedeemed = { listId ->
                    navController.navigate(Routes.list(listId)) {
                        popUpTo(Routes.REDEEM_PATTERN) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.navigate(Routes.OVERVIEW) {
                        popUpTo(Routes.REDEEM_PATTERN) { inclusive = true }
                    }
                },
                // No account can take it yet: sign in (the token is already stashed). After a
                // successful login, LoginViewModel.startDestinationAfterLogin() routes back into
                // redeem (T-28), for the account that signed in (T-292).
                onNeedsLogin = { loginRoute ->
                    navController.navigate(loginRoute) {
                        popUpTo(Routes.REDEEM_PATTERN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            AppDrawerScaffold(navController = navController, title = stringResource(R.string.nav_settings)) {
                SettingsScreen(selectedLocale = selectedLocale, onSelectLocale = localeViewModel::setLocale)
            }
        }
        composable(Routes.ADMIN_PATTERN) {
            // The same instance AdminScreen would get: this entry's. Its server under the title,
            // since each admin account opens its own (T-300).
            val adminViewModel: AdminViewModel = hiltViewModel()
            AppDrawerScaffold(
                navController = navController,
                title = stringResource(R.string.nav_server_admin),
                subtitle = adminViewModel.server,
            ) {
                AdminScreen(viewModel = adminViewModel)
            }
        }
        composable(Routes.ABOUT) {
            // Opening About asks the server right away (T-149; settings did it until T-224): past
            // the twelve-hour interval, with the ordinary prompt if there is something newer and a
            // status line either way. The block it answers lives on this screen now.
            val updateStatus by updateViewModel.status.collectAsStateWithLifecycle()
            val autoCheckEnabled by updateViewModel.autoCheckEnabled.collectAsStateWithLifecycle()
            AppDrawerScaffold(navController = navController, title = stringResource(R.string.nav_about)) {
                AboutScreen(
                    updateStatus = updateStatus,
                    autoCheckEnabled = autoCheckEnabled,
                    onSetAutoCheckEnabled = { updateViewModel.setAutoCheckEnabled(it) },
                    onOpened = { updateViewModel.checkNow() },
                )
            }
        }
    }

    // Rendered outside the NavHost so it survives a screen change, but suppressed on Login: an
    // update prompt stacked on "please log in" is noise, and there is nothing to update to until
    // a server is configured anyway.
    val onLoginScreen = navController.currentBackStackEntryAsState().value?.destination?.route == Routes.LOGIN_PATTERN
    // Bound to a local rather than used through ?.let { }: that lambda is not a @Composable
    // context, so neither the dialog nor LocalContext.current can be called inside one.
    // What an outdated account's "Check for update" found when there is nothing to install (T-304).
    val checkNotice by updateViewModel.checkNotice.collectAsStateWithLifecycle()
    checkNotice?.let { notice ->
        LocalizedAlertDialog(
            onDismissRequest = { updateViewModel.dismissCheckNotice() },
            text = {
                Text(
                    stringResource(
                        if (notice is UpdateStatus.UpToDate) R.string.update_required_unavailable else R.string.update_check_failed,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { updateViewModel.dismissCheckNotice() }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    val update = availableUpdate
    if (update != null && !onLoginScreen) {
        val context = LocalContext.current
        LocalizedAlertDialog(
            // Dismissing by tapping outside counts as having been asked, same as Skip.
            onDismissRequest = { updateViewModel.dismiss() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Text(stringResource(R.string.update_available_body, update.version, BuildConfig.VERSION_NAME))
            },
            confirmButton = {
                TextButton(onClick = {
                    openDownload(context, update.downloadUrl)
                    updateViewModel.dismiss()
                }) { Text(stringResource(R.string.action_update)) }
            },
            dismissButton = {
                // "Skip", not "Later": declining is final for this version.
                TextButton(onClick = { updateViewModel.dismiss() }) {
                    Text(stringResource(R.string.action_skip))
                }
            },
        )
    }
}

/**
 * Opens the app package the server is offering (T-135), for the ordinary prompt, the blocking
 * "update required" screen (T-240) and the login screen's "app too old" message (T-298) alike.
 *
 * Handed to the system rather than downloaded in-app: no extra permission, no installer session to
 * babysit, and the user gets the standard install flow they already know. NEW_TASK because this
 * leaves our task entirely.
 */
private fun openDownload(context: Context, downloadUrl: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, downloadUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // No browser/download handler on this device. Nothing useful to say, and crashing over
        // this would be worse — on the blocking screen it would also take the only way out.
    }
}

/** Top-level scaffold with a menu drawer (Notes: menu -> overview, accounts, settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDrawerScaffold(
    navController: NavHostController,
    title: String,
    drawerViewModel: DrawerViewModel = hiltViewModel(),
    // A second, smaller line under the title (T-292): the list's account, with several accounts.
    subtitle: String? = null,
    // When set, the top-bar title becomes tappable (the list screen uses it to jump to Overview).
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    syncStatusViewModel: SyncStatusViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val syncState by syncStatusViewModel.state.collectAsStateWithLifecycle()
    val adminAccounts by drawerViewModel.adminAccounts.collectAsStateWithLifecycle()
    val hasServer by drawerViewModel.hasServer.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var isJoinDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isAdminChooserOpen by rememberSaveable { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // Listed one by one rather than driven off a list of destinations: two of these
                // navigate and one opens a dialog, so a data-driven loop could only ever cover
                // part of the menu and the odd one out had to be appended after it — which is how
                // "Join a list" ended up below "Account". Written out, the source order IS the
                // drawer order.
                fun navigateTo(route: String) {
                    scope.launch { drawerState.close() }
                    if (route != currentRoute) navController.navigate(route)
                }

                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.Default.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_overview)) },
                    selected = currentRoute == Routes.OVERVIEW,
                    onClick = { navigateTo(Routes.OVERVIEW) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                // Three groups (T-307): where your lists are; getting at more of them (joining one,
                // the accounts they live in); and the app itself.
                DrawerDivider(1)
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_join_list)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        isJoinDialogOpen = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                // Who is signed in where, and adding another (T-292): the drawer's "Log out" went,
                // since nobody signs out; a server does, and the account's row says so.
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_accounts)) },
                    selected = currentRoute == Routes.ACCOUNTS,
                    onClick = { navigateTo(Routes.ACCOUNTS) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                DrawerDivider(2)
                NavigationDrawerItem(
                    // "Settings", as the screen it opens is titled (T-170); it was "Account".
                    icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                    selected = currentRoute == Routes.SETTINGS,
                    onClick = { navigateTo(Routes.SETTINGS) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                // Administering a server is not a personal preference, so it sits beside
                // Settings rather than inside it (T-220). Hiding it from a non-admin is an
                // affordance only — the server enforces admin on every /admin route regardless
                // of what this drawer offers. With several admin accounts it asks which (T-307).
                if (adminAccounts.isNotEmpty()) {
                    NavigationDrawerItem(
                        icon = { Icon(imageVector = Icons.Default.Build, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_server_admin)) },
                        selected = currentRoute == Routes.ADMIN_PATTERN,
                        onClick = {
                            scope.launch { drawerState.close() }
                            val only = adminAccounts.singleOrNull()
                            if (only != null) navController.openAdmin(only.id) else isAdminChooserOpen = true
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                // Last (T-224): what the app is, not something you do with it.
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_about)) },
                    selected = currentRoute == Routes.ABOUT,
                    onClick = { navigateTo(Routes.ABOUT) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        val titleModifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier
                        if (subtitle == null) {
                            Text(title, modifier = titleModifier)
                        } else {
                            Column(modifier = titleModifier) {
                                Text(title)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag(APP_BAR_SUBTITLE_TAG),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = stringResource(R.string.nav_menu))
                        }
                    },
                    actions = {
                        actions()
                        // One sync status on every screen, where the web keeps it too (T-178): a
                        // quiet dot, with the full "Synced 5 min ago" sentence as its description.
                        // The worst of every account; tapping it opens Accounts, where each one
                        // shows its own (T-292).
                        // None with only the local area, which never syncs (T-293).
                        if (hasServer) {
                            SyncStatusMarker(
                                state = syncState,
                                nowMs = rememberTickingNowMs(),
                                modifier = Modifier
                                    .clickable {
                                        if (currentRoute != Routes.ACCOUNTS) navController.navigate(Routes.ACCOUNTS)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    }

    if (isAdminChooserOpen) {
        AdminChooserDialog(
            accounts = adminAccounts,
            onChoose = { id ->
                isAdminChooserOpen = false
                navController.openAdmin(id)
            },
            onDismiss = { isAdminChooserOpen = false },
        )
    }

    if (isJoinDialogOpen) {
        RedeemDialog(
            onRedeemed = { listId ->
                isJoinDialogOpen = false
                navController.navigate(Routes.list(listId))
            },
            onDismiss = { isJoinDialogOpen = false },
            // A pasted link for a server this phone has no account on (T-292).
            onNeedsLogin = { loginRoute ->
                isJoinDialogOpen = false
                navController.navigate(loginRoute)
            },
        )
    }
}

/** The line between two groups of the drawer (T-307). */
@Composable
private fun DrawerDivider(index: Int) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp).testTag("drawer-divider-$index"))
}

/** Opens [accountId]'s admin console, unless it is already the screen shown. */
private fun NavHostController.openAdmin(accountId: String) {
    val current = currentBackStackEntry
    val showing = current?.destination?.route == Routes.ADMIN_PATTERN &&
        current.arguments?.getString(Routes.ACCOUNT_ID_ARG) == accountId
    if (!showing) navigate(Routes.admin(accountId))
}

/** Which admin account's console "Server admin" opens, with several (T-307): each as email · server. */
@Composable
private fun AdminChooserDialog(accounts: List<AccountEntity>, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    LocalizedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_server_admin_choose)) },
        text = {
            Column {
                accounts.forEach { account ->
                    Text(
                        accountLineText(account),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(account.id) }
                            .padding(vertical = 12.dp)
                            .testTag("admin-account-" + account.id),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A sub-screen's top bar with a back arrow and no drawer: the add and re-sign-in forms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) { content() }
    }
}

/**
 * The invite links the app opens (T-292): `https://<host>/invite/<token>`, and the same under a
 * mount path of up to three segments, as a server set up under `/shopping` shares them.
 */
internal val INVITE_LINK_PATTERNS = listOf(
    "https://{host}/invite/{${Routes.TOKEN_ARG}}",
    "https://{host}/{p1}/invite/{${Routes.TOKEN_ARG}}",
    "https://{host}/{p1}/{p2}/invite/{${Routes.TOKEN_ARG}}",
    "https://{host}/{p1}/{p2}/{p3}/invite/{${Routes.TOKEN_ARG}}",
)

/**
 * The invite link a redeem destination was opened for: the route's own argument, or the tapped
 * link itself when the destination came from a deep link. It names the invite's server (T-292).
 */
internal fun inviteLinkOf(arguments: Bundle?): String? {
    if (arguments == null) return null
    arguments.getString(Routes.LINK_ARG)?.let { return it }
    val intent = BundleCompat.getParcelable(arguments, NavController.KEY_DEEP_LINK_INTENT, Intent::class.java)
    return intent?.data?.toString()
}

/** The app bar's subtitle line, for tests. */
internal const val APP_BAR_SUBTITLE_TAG = "app_bar_subtitle"

@Composable
private fun PlaceholderScreen(title: String) {
    Text(text = title, modifier = Modifier.padding(16.dp))
}
