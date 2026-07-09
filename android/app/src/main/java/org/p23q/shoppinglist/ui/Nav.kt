package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.ui.login.LoginScreen
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.p23q.shoppinglist.ui.overview.OverviewScreen

/** Route patterns and builders for [ShoppingListNavHost]. */
object Routes {
    const val LOGIN = "login"
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"

    const val LIST_ID_ARG = "listId"
    const val LIST_PATTERN = "list/{$LIST_ID_ARG}"
    const val REGISTRY_PATTERN = "registry/{$LIST_ID_ARG}"
    const val LIST_PROPS_PATTERN = "listProps/{$LIST_ID_ARG}"

    fun list(listId: String) = "list/$listId"
    fun registry(listId: String) = "registry/$listId"
    fun listProps(listId: String) = "listProps/$listId"
}

/** Destinations reachable from the drawer menu (Notes: overview + account entries). */
private data class DrawerDestination(val route: String, val label: String)

private val drawerDestinations = listOf(
    DrawerDestination(Routes.OVERVIEW, "Overview"),
    DrawerDestination(Routes.SETTINGS, "Account"),
)

@Composable
fun ShoppingListNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = { startDestination ->
                    navController.navigate(startDestination) {
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
            val listId = backStackEntry.arguments?.getString(Routes.LIST_ID_ARG)
            AppDrawerScaffold(navController = navController, title = "List") {
                PlaceholderScreen(title = "List $listId")
            }
        }
        composable(Routes.REGISTRY_PATTERN) { backStackEntry ->
            val listId = backStackEntry.arguments?.getString(Routes.LIST_ID_ARG)
            AppDrawerScaffold(navController = navController, title = "Registry") {
                PlaceholderScreen(title = "Registry $listId")
            }
        }
        composable(Routes.LIST_PROPS_PATTERN) { backStackEntry ->
            val listId = backStackEntry.arguments?.getString(Routes.LIST_ID_ARG)
            AppDrawerScaffold(navController = navController, title = "List properties") {
                PlaceholderScreen(title = "List properties $listId")
            }
        }
        composable(Routes.SETTINGS) {
            AppDrawerScaffold(navController = navController, title = "Settings") {
                PlaceholderScreen(title = "Settings")
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
}

@Composable
private fun PlaceholderScreen(title: String) {
    Text(text = title, modifier = Modifier.padding(16.dp))
}
