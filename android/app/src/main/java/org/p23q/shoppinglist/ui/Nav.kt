package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.ui.item.AddItemDialog
import org.p23q.shoppinglist.ui.item.EditItemDialog
import org.p23q.shoppinglist.ui.list.ListScreen
import org.p23q.shoppinglist.ui.listprops.ListPropsScreen
import org.p23q.shoppinglist.ui.login.LoginScreen
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.p23q.shoppinglist.ui.overview.OverviewScreen
import org.p23q.shoppinglist.ui.redeem.RedeemDialog
import org.p23q.shoppinglist.ui.redeem.RedeemScreen
import org.p23q.shoppinglist.ui.registry.RegistryScreen
import org.p23q.shoppinglist.ui.settings.SettingsScreen

/** Route patterns and builders for [ShoppingListNavHost]. */
object Routes {
    const val LOGIN = "login"
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"

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
 * Where an already-authenticated session should land: the last-opened list if one is remembered
 * (Notes: "on login, open the list the user last had open"), otherwise the overview. Shared by the
 * cold-start resume in [org.p23q.shoppinglist.MainActivity] and the post-login navigation in
 * [org.p23q.shoppinglist.ui.login.LoginViewModel].
 */
fun authedStartDestination(lastOpenedListId: String?): String =
    lastOpenedListId?.let { Routes.list(it) } ?: Routes.OVERVIEW

/** Destinations reachable from the drawer menu (Notes: overview + account entries). */
private data class DrawerDestination(val route: String, val label: String)

private val drawerDestinations = listOf(
    DrawerDestination(Routes.OVERVIEW, "Overview"),
    DrawerDestination(Routes.SETTINGS, "Account"),
)

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

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = { destination ->
                    navController.navigate(destination) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.OVERVIEW) {
            AppDrawerScaffold(navController = navController, title = "Overview") {
                OverviewScreen(onOpenList = { listId -> navController.navigate(Routes.list(listId)) })
            }
        }
        composable(Routes.LIST_PATTERN) { backStackEntry ->
            val listId = checkNotNull(backStackEntry.arguments?.getString(Routes.LIST_ID_ARG))
            AppDrawerScaffold(navController = navController, title = "List") {
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
            AppDrawerScaffold(navController = navController, title = "Registry") {
                var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }

                RegistryScreen(onEditItem = { itemId -> editingItemId = itemId })

                editingItemId?.let { itemId ->
                    EditItemDialog(itemId = itemId, onDismiss = { editingItemId = null })
                }
            }
        }
        composable(Routes.LIST_PROPS_PATTERN) {
            AppDrawerScaffold(navController = navController, title = "List properties") {
                ListPropsScreen(
                    onLeft = {
                        navController.navigate(Routes.OVERVIEW) {
                            popUpTo(Routes.OVERVIEW) { inclusive = true }
                            launchSingleTop = true
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
            )
        }
        composable(Routes.SETTINGS) {
            AppDrawerScaffold(navController = navController, title = "Settings") {
                SettingsScreen(
                    onAccountDeleted = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
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
                drawerDestinations.forEach { destination ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = if (destination.route == Routes.OVERVIEW) {
                                    Icons.Default.List
                                } else {
                                    Icons.Default.AccountCircle
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                        selected = destination.route == currentRoute,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (destination.route != currentRoute) {
                                navController.navigate(destination.route)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Join list") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        isJoinDialogOpen = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(imageVector = Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                    label = { Text("Log out") },
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
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
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
