package org.p23q.shoppinglist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.item.AddItemDialog
import org.p23q.shoppinglist.ui.item.EditItemDialog
import org.p23q.shoppinglist.ui.list.ListScreen
import org.p23q.shoppinglist.ui.listprops.ListPropsScreen
import org.p23q.shoppinglist.ui.login.LoginScreen
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.p23q.shoppinglist.ui.overview.OverviewScreen
import org.p23q.shoppinglist.ui.redeem.RedeemDialog
import org.p23q.shoppinglist.ui.redeem.RedeemScreen
import org.p23q.shoppinglist.ui.admin.AdminScreen
import org.p23q.shoppinglist.ui.registry.RegistryScreen
import org.p23q.shoppinglist.ui.settings.SettingsScreen
import androidx.compose.ui.res.stringResource

/** Route patterns and builders for [ShoppingListNavHost]. */
object Routes {
    const val LOGIN = "login"
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"
    const val ADMIN = "admin"

    const val LIST_ID_ARG = "listId"
    const val LIST_PATTERN = "list/{$LIST_ID_ARG}"
    const val REGISTRY_PATTERN = "registry/{$LIST_ID_ARG}"
    const val LIST_PROPS_PATTERN = "listProps/{$LIST_ID_ARG}"

    const val TOKEN_ARG = "token"
    const val REDEEM_PATTERN = "redeem/{$TOKEN_ARG}"

    fun list(listId: String) = "list/$listId"
    fun registry(listId: String) = "registry/$listId"
    fun listProps(listId: String) = "listProps/$listId"
    fun redeem(token: String) = "redeem/$token"
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

/**
 * Where an already-authenticated session should land: the last-opened list if one is remembered
 * (Notes: "on login, open the list the user last had open"), otherwise the overview. Shared by the
 * cold-start resume in [org.p23q.shoppinglist.MainActivity] and the post-login navigation in
 * [org.p23q.shoppinglist.ui.login.LoginViewModel].
 */
fun authedStartDestination(lastOpenedListId: String?): String =
    lastOpenedListId?.let { Routes.list(it) } ?: Routes.OVERVIEW

@Composable
fun ShoppingListNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.LOGIN,
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    // A token that the server rejects mid-session (revoked/expired) surfaces once, at the
    // interceptor, as a forced-logout signal; clear the dead session and return to Login rather
    // than leaving the user on a silently-stale screen whose every request 401s.
    LaunchedEffect(Unit) {
        rootViewModel.forcedLogout.collect {
            rootViewModel.onForcedLogout().join()
            navController.navigate(Routes.LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Obtained here, not inside each screen: Nav is already inside the Hilt graph, and keeping
    // the screens Hilt-free is what lets them be rendered directly in unit tests (T-127).
    val localeViewModel: LocaleViewModel = hiltViewModel()
    val selectedLocale by localeViewModel.locale.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen(
                selectedLocale = selectedLocale,
                onSelectLocale = localeViewModel::setLocale,
                onLoginSuccess = { destination ->
                    navController.navigate(destination) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
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
                OverviewScreen(onOpenList = { listId -> navController.navigate(Routes.list(listId)) })
            }
        }
        composable(Routes.LIST_PATTERN) { backStackEntry ->
            val listId = checkNotNull(backStackEntry.arguments?.getString(Routes.LIST_ID_ARG))
            AppDrawerScaffold(
                navController = navController,
                title = liveListTitle(stringResource(R.string.nav_list)),
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
        composable(Routes.REGISTRY_PATTERN) {
            AppDrawerScaffold(navController = navController, title = liveListTitle(stringResource(R.string.nav_registry))) {
                var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }

                RegistryScreen(onEditItem = { itemId -> editingItemId = itemId })

                editingItemId?.let { itemId ->
                    EditItemDialog(itemId = itemId, onDismiss = { editingItemId = null })
                }
            }
        }
        composable(Routes.LIST_PROPS_PATTERN) {
            AppDrawerScaffold(navController = navController, title = liveListTitle(stringResource(R.string.nav_list_properties))) {
                ListPropsScreen(
                    onLeft = {
                        navController.navigate(Routes.OVERVIEW) {
                            popUpTo(Routes.OVERVIEW) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onDuplicated = { newListId ->
                        navController.navigate(Routes.list(newListId)) {
                            popUpTo(Routes.OVERVIEW)
                        }
                    },
                )
            }
        }
        composable(
            route = Routes.REDEEM_PATTERN,
            // The manifest's intent-filter (any https host + /invite/ prefix) gets the OS to
            // launch this Activity; this deep link is what routes the resulting Intent to this
            // destination and extracts the token, once the Activity is already running.
            deepLinks = listOf(navDeepLink { uriPattern = "https://{host}/invite/{${Routes.TOKEN_ARG}}" }),
        ) { backStackEntry ->
            val token = checkNotNull(backStackEntry.arguments?.getString(Routes.TOKEN_ARG))
            RedeemScreen(
                token = token,
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
                // Logged out: go to Login (the token is already stashed). After a successful login,
                // LoginViewModel.startDestinationAfterLogin() routes back into redeem (T-28).
                onNeedsLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            AppDrawerScaffold(navController = navController, title = stringResource(R.string.nav_settings)) {
                SettingsScreen(
                    selectedLocale = selectedLocale,
                    onSelectLocale = localeViewModel::setLocale,
                    onAccountDeleted = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenAdmin = { navController.navigate(Routes.ADMIN) },
                )
            }
        }
        composable(Routes.ADMIN) {
            AppDrawerScaffold(navController = navController, title = stringResource(R.string.nav_server_admin)) {
                AdminScreen()
            }
        }
    }
}

/** Top-level scaffold with a menu drawer (Notes: menu -> overview + account/settings, user info, logout). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDrawerScaffold(
    navController: NavHostController,
    title: String,
    loginViewModel: LoginViewModel = hiltViewModel(),
    // When set, the top-bar title becomes tappable (the list screen uses it to jump to Overview).
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var isJoinDialogOpen by rememberSaveable { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                loginViewModel.loggedInEmail?.let { email ->
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider()
                }
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
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_account)) },
                    selected = currentRoute == Routes.SETTINGS,
                    onClick = { navigateTo(Routes.SETTINGS) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_log_out)) },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            loginViewModel.logout().join()
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
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
                        Text(title, modifier = titleModifier)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = stringResource(R.string.nav_menu))
                        }
                    },
                    actions = actions,
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    }

    if (isJoinDialogOpen) {
        RedeemDialog(
            onRedeemed = { listId ->
                isJoinDialogOpen = false
                navController.navigate(Routes.list(listId))
            },
            onDismiss = { isJoinDialogOpen = false },
        )
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Text(text = title, modifier = Modifier.padding(16.dp))
}
