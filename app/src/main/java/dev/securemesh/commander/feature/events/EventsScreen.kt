package dev.securemesh.commander.feature.events

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.export.LocalDocumentExporter
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.launch

@Composable
fun EventsScreen(viewModel: EventsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.allowed) { EmptyState("Event log unavailable", "VIEW_SYSTEM_LOG was not granted for this authenticated session."); return }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<MeshEvent?>(null) }
    var pending by remember { mutableStateOf("") }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { LocalDocumentExporter.write(context, it, pending) } }
    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { LocalDocumentExporter.write(context, it, pending) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("EVENT LOG", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); Text("Persistent Room-backed timeline of radio, routing, message, GPS, security, system and SOS events.", color = SecureMeshColors.Muted) }
        item { OutlinedTextField(state.query, viewModel::query, label = { Text("Search events") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(state.category == null, { viewModel.category(null) }, { Text("ALL") })
                EventCategory.entries.take(4).forEach { c -> FilterChip(state.category == c, { viewModel.category(c) }, { Text(c.name) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { EventCategory.entries.drop(4).forEach { c -> FilterChip(state.category == c, { viewModel.category(c) }, { Text(c.name) }) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { pending = viewModel.exportCsv(); createCsv.launch("securemesh-events.csv") } }) { Text("EXPORT CSV") }
                OutlinedButton(onClick = { scope.launch { pending = viewModel.exportJson(); createJson.launch("securemesh-events.json") } }) { Text("EXPORT JSON") }
            }
        }
        if (state.items.isEmpty()) item { EmptyState("No events", "The selected filter has no locally stored events.") }
        else items(state.items, key = { it.id }) { e ->
            TechnicalCard(e.title, Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StatusChip(e.category.name, categoryColor(e.category)); Text(clockLabel(e.timestampEpochMs), color = SecureMeshColors.Muted) }
                Text(e.details); if (e.nodeId != null) Text("NODE ${e.nodeId}", color = SecureMeshColors.Cyan)
                TextButton(onClick = { selected = e }) { Text("EVENT DETAILS") }
            }
        }
    }
    selected?.let { e -> AlertDialog(onDismissRequest = { selected = null }, confirmButton = { TextButton(onClick = { selected = null }) { Text("CLOSE") } }, title = { Text(e.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${clockLabel(e.timestampEpochMs)} · ${e.category}", color = SecureMeshColors.Muted); Text(e.details); Text("Event ID ${e.id}", style = MaterialTheme.typography.bodySmall); e.nodeId?.let { Text("Node $it") } } }) }
}
private fun categoryColor(category: EventCategory) = when(category){EventCategory.SOS->SecureMeshColors.Critical;EventCategory.ROUTING,EventCategory.RADIO->SecureMeshColors.Cyan;EventCategory.SECURITY->SecureMeshColors.Warning;else->SecureMeshColors.Healthy}
