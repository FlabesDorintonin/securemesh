package dev.securemesh.commander.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import dev.securemesh.commander.feature.dashboard.*
import dev.securemesh.commander.feature.diagnostics.*
import dev.securemesh.commander.feature.discovery.*
import dev.securemesh.commander.feature.events.*
import dev.securemesh.commander.feature.fieldtest.*
import dev.securemesh.commander.feature.map.MapScreen
import dev.securemesh.commander.feature.messages.*
import dev.securemesh.commander.feature.more.MoreScreen
import dev.securemesh.commander.feature.network.*
import dev.securemesh.commander.feature.nodes.*
import dev.securemesh.commander.feature.routes.*
import dev.securemesh.commander.feature.search.*
import dev.securemesh.commander.feature.settings.*
import dev.securemesh.commander.feature.sos.SosOverlay
import dev.securemesh.commander.feature.welcome.*

private object RootRoute {
    const val WELCOME = "welcome"
    const val DISCOVERY = "discovery"
    const val PROTOCOL = "protocol"
    const val MAIN = "main"
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private fun itemsFor(session: SecureMeshSession?): List<NavItem> = buildList {
    add(NavItem("home", "Главная", Icons.Rounded.Home))
    if (UiAccessPolicy.canShowMessages(session)) {
        add(NavItem("messages", "Чаты", Icons.Rounded.ChatBubble))
    }
    if (UiAccessPolicy.canShowNodes(session)) {
        add(NavItem("nodes", "Узлы", Icons.Rounded.People))
    } else {
        session?.let { add(NavItem("node/${it.localNodeIdentity.nodeId}", "Мой узел", Icons.Rounded.People)) }
    }
    add(NavItem("more", "Ещё", Icons.Rounded.MoreHoriz))
}

@Composable
fun SecureMeshRoot(repository: SecureMeshRepository) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = RootRoute.WELCOME,
        enterTransition = {
            fadeIn(tween(230)) + slideInHorizontally(tween(260)) { it / 12 }
        },
        exitTransition = {
            fadeOut(tween(150)) + slideOutHorizontally(tween(190)) { -it / 18 }
        },
        popEnterTransition = {
            fadeIn(tween(210)) + slideInHorizontally(tween(240)) { -it / 14 }
        },
        popExitTransition = {
            fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { it / 18 }
        },
    ) {
        composable(RootRoute.WELCOME) {
            val vm: WelcomeViewModel = viewModel(factory = viewModelFactory { WelcomeViewModel(repository) })
            WelcomeScreen(
                vm,
                onConnect = { nav.navigate(RootRoute.DISCOVERY) },
                onDemo = { nav.navigate(RootRoute.MAIN) { popUpTo(RootRoute.WELCOME) { inclusive = true } } },
                onAutoConnected = { secure -> nav.navigate(if (secure) RootRoute.MAIN else RootRoute.PROTOCOL) },
            )
        }
        composable(RootRoute.DISCOVERY) {
            val vm: DiscoveryViewModel = viewModel(factory = viewModelFactory { DiscoveryViewModel(repository) })
            DiscoveryScreen(vm, { nav.popBackStack() }, { nav.navigate(RootRoute.PROTOCOL) })
        }
        composable(RootRoute.PROTOCOL) {
            val vm: DiscoveryViewModel = viewModel(factory = viewModelFactory { DiscoveryViewModel(repository) })
            val ui by vm.uiState.collectAsStateWithLifecycle()
            val connected = ui.connection as? MeshConnectionState.Connected
            if (connected == null) {
                LaunchedEffect(ui.connection) { nav.popBackStack(RootRoute.DISCOVERY, false) }
            } else {
                ProtocolUnavailableScreen(
                    connected,
                    onDisconnect = { vm.disconnect(); nav.popBackStack(RootRoute.DISCOVERY, false) },
                    onBack = { nav.popBackStack(RootRoute.DISCOVERY, false) },
                )
            }
        }
        composable(RootRoute.MAIN) { MainShell(repository) }
    }
}

@Composable
private fun MainShell(repository: SecureMeshRepository) {
    val nav = rememberNavController()
    val vm: RootViewModel = viewModel(factory = viewModelFactory { RootViewModel(repository) })
    val session by vm.session.collectAsStateWithLifecycle()
    val sos by vm.sos.collectAsStateWithLifecycle()
    val items = itemsFor(session)

    MeshBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxSize()) {
                    PrimaryRail(nav, items, Modifier.width(108.dp))
                    Box(Modifier.weight(1f)) { MainNavHost(nav, repository, session) }
                }
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = { PrimaryBar(nav, items) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) { MainNavHost(nav, repository, session) }
                }
            }

            sos?.takeIf { UiAccessPolicy.canShowSos(session) }?.let { alert ->
                val canMap = UiAccessPolicy.canShowMap(session)
                val canNode = UiAccessPolicy.canShowNodes(session) || alert.nodeId == session?.localNodeIdentity?.nodeId
                val canAck = session?.can(SessionPermission.ACKNOWLEDGE_SOS) == true
                SosOverlay(
                    alert,
                    canMap,
                    canNode,
                    canAck,
                    { nav.navigate("map") },
                    { nav.navigate("node/${alert.nodeId}") },
                    { vm.acknowledge(alert.id) },
                )
            }
        }
    }
}

@Composable
private fun MainNavHost(nav: NavHostController, repository: SecureMeshRepository, session: SecureMeshSession?) {
    NavHost(
        navController = nav,
        startDestination = "home",
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            fadeIn(tween(210)) + slideInVertically(tween(240)) { it / 20 }
        },
        exitTransition = {
            fadeOut(tween(140)) + slideOutVertically(tween(170)) { -it / 28 }
        },
        popEnterTransition = {
            fadeIn(tween(190)) + slideInVertically(tween(220)) { -it / 24 }
        },
        popExitTransition = {
            fadeOut(tween(130)) + slideOutVertically(tween(160)) { it / 28 }
        },
    ) {
        composable("home") {
            val vm: DashboardViewModel = viewModel(factory = viewModelFactory { DashboardViewModel(repository) })
            DashboardScreen(
                viewModel = vm,
                onNodes = {
                    val target = if (UiAccessPolicy.canShowNodes(session)) "nodes"
                    else session?.let { "node/${it.localNodeIdentity.nodeId}" } ?: "home"
                    nav.navigate(target)
                },
                onMessages = { if (UiAccessPolicy.canShowMessages(session)) nav.navigate("messages") },
                onEvents = { if (UiAccessPolicy.canShowSystemLog(session)) nav.navigate("events") },
                onMore = { nav.navigate("more") },
            )
        }
        composable("nodes") {
            val vm: NodesViewModel = viewModel(factory = viewModelFactory { NodesViewModel(repository) })
            NodesScreen(vm) { nav.navigate("node/$it") }
        }
        composable("node/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: NodeDetailsViewModel = viewModel(factory = viewModelFactory { NodeDetailsViewModel(repository, id) })
            NodeDetailsScreen(vm) { nav.popBackStack() }
        }
        composable("messages") { MessagesScreen(viewModel(factory = viewModelFactory { MessagesViewModel(repository) })) }
        composable("map") { MapScreen(viewModel(factory = viewModelFactory { NetworkViewModel(repository) }), onNode = { nav.navigate("node/$it") }) }
        composable("more") { MoreScreen(session) { nav.navigate(it) } }
        composable("topology") { TopologyScreen(viewModel(factory = viewModelFactory { NetworkViewModel(repository) })) { nav.navigate("node/$it") } }
        composable("routes") { RoutesScreen(viewModel(factory = viewModelFactory { RoutesViewModel(repository) })) }
        composable("fieldtest") { FieldTestScreen(viewModel(factory = viewModelFactory { FieldTestViewModel(repository) })) }
        composable("events") { EventsScreen(viewModel(factory = viewModelFactory { EventsViewModel(repository) })) }
        composable("diagnostics") { DiagnosticsScreen(viewModel(factory = viewModelFactory { DiagnosticsViewModel(repository) })) }
        composable("settings") { SettingsScreen(viewModel(factory = viewModelFactory { SettingsViewModel(repository) })) }
        composable("search") { SearchScreen(viewModel(factory = viewModelFactory { SearchViewModel(repository) })) { nav.navigate("node/$it") } }
    }
}

private fun isMoreRoute(route: String?): Boolean = route in setOf(
    "more", "map", "topology", "routes", "fieldtest", "events", "diagnostics", "settings", "search",
)

private fun isSelected(current: String?, item: NavItem): Boolean = when {
    item.route == "more" -> isMoreRoute(current)
    current == "node/{id}" && (item.route == "nodes" || item.route.startsWith("node/")) -> true
    else -> current == item.route
}

private fun navigateTopLevel(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun PrimaryBar(nav: NavHostController, items: List<NavItem>) {
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    Surface(
        color = SecureMeshColors.Navigation.copy(alpha = .98f),
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .55f)),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = NavigationBarDefaults.windowInsets,
        ) {
            items.forEach { item ->
                NavigationBarItem(
                    selected = isSelected(current, item),
                    onClick = { navigateTopLevel(nav, item.route) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SecureMeshColors.CyanHot,
                        selectedTextColor = SecureMeshColors.CyanHot,
                        indicatorColor = SecureMeshColors.Cyan.copy(alpha = .18f),
                        unselectedIconColor = SecureMeshColors.Muted,
                        unselectedTextColor = SecureMeshColors.Muted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PrimaryRail(nav: NavHostController, items: List<NavItem>, modifier: Modifier = Modifier) {
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    NavigationRail(modifier = modifier, containerColor = SecureMeshColors.Navigation.copy(alpha = .98f)) {
        Spacer(Modifier.height(18.dp))
        items.forEach { item ->
            NavigationRailItem(
                selected = isSelected(current, item),
                onClick = { navigateTopLevel(nav, item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = SecureMeshColors.CyanHot,
                    selectedTextColor = SecureMeshColors.CyanHot,
                    indicatorColor = SecureMeshColors.Cyan.copy(alpha = .18f),
                    unselectedIconColor = SecureMeshColors.Muted,
                    unselectedTextColor = SecureMeshColors.Muted,
                ),
            )
        }
    }
}
