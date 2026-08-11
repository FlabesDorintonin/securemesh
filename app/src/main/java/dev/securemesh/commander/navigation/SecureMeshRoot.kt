package dev.securemesh.commander.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

private object RootRoute{const val WELCOME="welcome";const val DISCOVERY="discovery";const val PROTOCOL="protocol";const val MAIN="main"}
private data class NavItem(val route:String,val label:String,val glyph:String)
private fun itemsFor(s: SecureMeshSession?): List<NavItem> = buildList{add(NavItem("home","HOME","H"));if(UiAccessPolicy.canShowNodes(s))add(NavItem("nodes","NODES","N"))else s?.let{add(NavItem("node/${it.localNodeIdentity.nodeId}","NODE","N"))};if(UiAccessPolicy.canShowMessages(s))add(NavItem("messages","MESSAGES","M"));if(UiAccessPolicy.canShowMap(s))add(NavItem("map","MAP","P"));add(NavItem("more","MORE","+"))}
@Composable fun SecureMeshRoot(repository:SecureMeshRepository){val nav=rememberNavController();NavHost(nav,RootRoute.WELCOME){composable(RootRoute.WELCOME){val vm:WelcomeViewModel=viewModel(factory=viewModelFactory{WelcomeViewModel(repository)});WelcomeScreen(vm,{nav.navigate(RootRoute.DISCOVERY)},{nav.navigate(RootRoute.MAIN){popUpTo(RootRoute.WELCOME){inclusive=true}}},{secure->nav.navigate(if(secure)RootRoute.MAIN else RootRoute.PROTOCOL)})};composable(RootRoute.DISCOVERY){val vm:DiscoveryViewModel=viewModel(factory=viewModelFactory{DiscoveryViewModel(repository)});DiscoveryScreen(vm,{nav.popBackStack()},{nav.navigate(RootRoute.PROTOCOL)})};composable(RootRoute.PROTOCOL){val vm:DiscoveryViewModel=viewModel(factory=viewModelFactory{DiscoveryViewModel(repository)});val ui by vm.uiState.collectAsStateWithLifecycle();val connected=ui.connection as? MeshConnectionState.Connected;if(connected==null)LaunchedEffect(ui.connection){nav.popBackStack(RootRoute.DISCOVERY,false)}else ProtocolUnavailableScreen(connected,{vm.disconnect();nav.popBackStack(RootRoute.DISCOVERY,false)},{nav.popBackStack(RootRoute.DISCOVERY,false)})};composable(RootRoute.MAIN){MainShell(repository)}}}
@Composable private fun MainShell(repository:SecureMeshRepository){val nav=rememberNavController();val vm:RootViewModel=viewModel(factory=viewModelFactory{RootViewModel(repository)});val session by vm.session.collectAsStateWithLifecycle();val sos by vm.sos.collectAsStateWithLifecycle();val items=itemsFor(session);BoxWithConstraints(Modifier.fillMaxSize()){if(maxWidth>=760.dp)Row(Modifier.fillMaxSize()){PrimaryRail(nav,items,Modifier.width(94.dp));Box(Modifier.weight(1f)){MainNavHost(nav,repository,session)}}else Scaffold(bottomBar={PrimaryBar(nav,items)}){p->Box(Modifier.fillMaxSize().padding(p)){MainNavHost(nav,repository,session)}}};sos?.takeIf{UiAccessPolicy.canShowSos(session)}?.let{a->val canMap=UiAccessPolicy.canShowMap(session);val canNode=UiAccessPolicy.canShowNodes(session)||a.nodeId==session?.localNodeIdentity?.nodeId;val canAck=session?.can(SessionPermission.ACKNOWLEDGE_SOS)==true;SosOverlay(a,canMap,canNode,canAck,{nav.navigate("map")},{nav.navigate("node/${a.nodeId}")},{vm.acknowledge(a.id)})}}
@Composable private fun MainNavHost(nav:NavHostController,repository:SecureMeshRepository,session:SecureMeshSession?){NavHost(nav,"home"){composable("home"){val vm:DashboardViewModel=viewModel(factory=viewModelFactory{DashboardViewModel(repository)});DashboardScreen(vm,{val target=if(UiAccessPolicy.canShowNodes(session))"nodes" else session?.let{"node/${it.localNodeIdentity.nodeId}"}?:"home";nav.navigate(target)},{if(UiAccessPolicy.canShowSystemLog(session))nav.navigate("events")})};composable("nodes"){val vm:NodesViewModel=viewModel(factory=viewModelFactory{NodesViewModel(repository)});NodesScreen(vm){nav.navigate("node/$it")}};composable("node/{id}"){e->val id=e.arguments?.getString("id").orEmpty();val vm:NodeDetailsViewModel=viewModel(factory=viewModelFactory{NodeDetailsViewModel(repository,id)});NodeDetailsScreen(vm){nav.popBackStack()}};composable("messages"){MessagesScreen(viewModel(factory=viewModelFactory{MessagesViewModel(repository)}))};composable("map"){MapScreen(viewModel(factory=viewModelFactory{NetworkViewModel(repository)}),onNode={nav.navigate("node/$it")})};composable("more"){MoreScreen(session){nav.navigate(it)}};composable("topology"){TopologyScreen(viewModel(factory=viewModelFactory{NetworkViewModel(repository)})){nav.navigate("node/$it")}};composable("routes"){RoutesScreen(viewModel(factory=viewModelFactory{RoutesViewModel(repository)}))};composable("fieldtest"){FieldTestScreen(viewModel(factory=viewModelFactory{FieldTestViewModel(repository)}))};composable("events"){EventsScreen(viewModel(factory=viewModelFactory{EventsViewModel(repository)}))};composable("diagnostics"){DiagnosticsScreen(viewModel(factory=viewModelFactory{DiagnosticsViewModel(repository)}))};composable("settings"){SettingsScreen(viewModel(factory=viewModelFactory{SettingsViewModel(repository)}))};composable("search"){SearchScreen(viewModel(factory=viewModelFactory{SearchViewModel(repository)})){nav.navigate("node/$it")}}}}
@Composable
private fun PrimaryBar(nav: NavHostController, items: List<NavItem>) {
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    NavigationBar(containerColor = SecureMeshColors.Surface) {
        items.forEach { item ->
            NavigationBarItem(
                selected = current == item.route || (current == "node/{id}" && item.route.startsWith("node/")),
                onClick = { nav.navigate(item.route) { launchSingleTop = true } },
                icon = { Text(item.glyph) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun PrimaryRail(nav: NavHostController, items: List<NavItem>, modifier: Modifier = Modifier) {
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    NavigationRail(modifier = modifier, containerColor = SecureMeshColors.Surface) {
        Spacer(Modifier.height(18.dp))
        items.forEach { item ->
            NavigationRailItem(
                selected = current == item.route || (current == "node/{id}" && item.route.startsWith("node/")),
                onClick = { nav.navigate(item.route) { launchSingleTop = true } },
                icon = { Text(item.glyph) },
                label = { Text(item.label) },
            )
        }
    }
}
