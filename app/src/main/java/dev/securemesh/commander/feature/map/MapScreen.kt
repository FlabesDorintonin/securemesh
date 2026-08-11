package dev.securemesh.commander.feature.map

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.map.*
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.feature.network.NetworkViewModel

@Composable
fun MapScreen(
    viewModel: NetworkViewModel,
    provider: MeshMapProvider = LocalSchematicMapProvider,
    onNode: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.canMap) {
        EmptyState("Map unavailable", "GPS capability plus VIEW_OWN_POSITION or VIEW_TEAM_POSITIONS is required.")
        return
    }

    var selected by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(false) }
    val positioned = state.mapNodes.filter { it.position != null }

    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("MAP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "Only positions exposed to this authenticated session are rendered · ${provider.providerName}",
            color = SecureMeshColors.Muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(follow, { follow = !follow }, { Text("FOLLOW SELECTED") }, enabled = selected != null)
            OutlinedButton(onClick = { selected = null; follow = false }) { Text("CENTER ALL") }
            selected?.let { id -> OutlinedButton(onClick = { onNode(id) }) { Text("OPEN NODE") } }
        }
        Surface(
            Modifier.fillMaxWidth().weight(1f),
            color = SecureMeshColors.Surface,
            shape = MaterialTheme.shapes.large,
        ) {
            if (positioned.isEmpty()) {
                EmptyState("No authorized positions", "UNKNOWN is preferred to inventing GPS data.")
            } else {
                provider.Render(MapRenderState(positioned, selected, follow), Modifier.fillMaxSize()) { selected = it }
            }
        }
        selected?.let { id ->
            positioned.firstOrNull { it.id == id }?.position?.let { p ->
                TechnicalCard("Selected node") {
                    Text("$id · ${coordinate(p.latitude)}, ${coordinate(p.longitude)}")
                    Text(
                        "GPS ${p.status(System.currentTimeMillis())} · sats ${p.satellites ?: "UNKNOWN"} · HDOP ${p.hdop ?: "UNKNOWN"}",
                        color = SecureMeshColors.Muted,
                    )
                }
            }
        }
    }
}
