package dev.securemesh.commander.feature.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.SecureMeshSession
import dev.securemesh.commander.domain.service.UiAccessPolicy

private data class MoreDestination(val title: String, val description: String, val route: String)

@Composable
fun MoreScreen(session: SecureMeshSession?, open: (String) -> Unit) {
    val menuItems = buildList {
        if (UiAccessPolicy.canShowTopology(session)) add(MoreDestination("Network", "Directional topology", "topology"))
        if (UiAccessPolicy.canShowRoutes(session)) add(MoreDestination("Routes", "DIRECT / STATIC route state", "routes"))
        if (UiAccessPolicy.canRunFieldTest(session)) add(MoreDestination("Field Tests", "Local-node radio/routing tests", "fieldtest"))
        if (UiAccessPolicy.canShowSystemLog(session)) add(MoreDestination("Events", "Authorized event log", "events"))
        if (UiAccessPolicy.canShowDiagnostics(session)) add(MoreDestination("Diagnostics", "Session and network diagnostics", "diagnostics"))
        add(MoreDestination("Global Search", "Search only visible data", "search"))
        add(MoreDestination("Settings", "Bluetooth, storage and developer settings", "settings"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text("MORE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Tools are projected from capabilities + session permissions.", color = SecureMeshColors.Muted)
        }

        items(menuItems, key = { it.route }) { destination ->
            TechnicalCard(destination.title) {
                Text(destination.description, color = SecureMeshColors.Muted)
                TextButton(onClick = { open(destination.route) }) { Text("OPEN →") }
            }
        }
    }
}
