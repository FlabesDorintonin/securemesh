package dev.securemesh.commander.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.BuildConfig
import dev.securemesh.commander.core.ui.*

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.allowed) {
        EmptyState("Diagnostics unavailable", "NETWORK_DIAGNOSTICS capability plus VIEW_NETWORK_DIAGNOSTICS permission is required.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { Text("DIAGNOSTICS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black) }
        item { TechnicalCard("App") { DiagnosticRow("Version", BuildConfig.VERSION_NAME); DiagnosticRow("Build", BuildConfig.VERSION_CODE.toString()); DiagnosticRow("Transport", state.mode.name); DiagnosticRow("Demo profile", state.profile?.name ?: "NONE") } }
        item { TechnicalCard("Phone") { DiagnosticRow("Bluetooth", state.phoneBluetooth); DiagnosticRow("BLE permission", state.blePermission); Text("UNKNOWN is shown when the transport has not provided a current observation.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        item { TechnicalCard("Local SecureMesh node") { DiagnosticRow("Connection", state.connection::class.simpleName ?: "Unknown"); DiagnosticRow("Identity", state.session?.localNodeIdentity?.nodeId ?: "NOT IDENTIFIED"); DiagnosticRow("Auth", state.session?.authenticationState?.name ?: "NOT ESTABLISHED"); DiagnosticRow("Role", state.session?.localNodeIdentity?.role?.name ?: "UNKNOWN") } }
        item { TechnicalCard("Mesh") { DiagnosticRow("Visible nodes", state.nodes.toString()); DiagnosticRow("Directional links", state.links.toString()); DiagnosticRow("Routes", state.routes.toString()); DiagnosticRow("Message queue", state.messages.toString()); DiagnosticRow("Last visible event", state.events.firstOrNull()?.let { ageLabel(it.timestampEpochMs) } ?: "UNAVAILABLE") } }
        if (state.settings.developerMode) item {
            TechnicalCard("Developer") {
                Text("Raw event viewer", fontWeight = FontWeight.Bold)
                if (state.events.isEmpty()) Text("System events unavailable to this session.", color = SecureMeshColors.Muted)
                state.events.take(12).forEach { Text("${clockLabel(it.timestampEpochMs)} ${it.category}: ${it.title}", style = MaterialTheme.typography.bodySmall, color = SecureMeshColors.Muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("NORMAL","WEAK LINK","RELAY LOST","GPS LOST","MESSAGE RETRY","SOS").forEach { scenario -> AssistChip({ viewModel.scenario(scenario) }, { Text(scenario) }) } }
                OutlinedButton(onClick = viewModel::clearHistory) { Text("CLEAR LOCAL CACHE") }
            }
        } else item { Text("Raw BLE/debug functions are hidden. Enable Development Mode in Settings.", color = SecureMeshColors.Muted) }
    }
}

@Composable
private fun DiagnosticRow(label:String,value:String){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        Text(label,color=SecureMeshColors.Muted)
        Text(value,fontWeight=FontWeight.SemiBold)
    }
}
