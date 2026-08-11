package dev.securemesh.commander.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*

@Composable
fun SearchScreen(viewModel: SearchViewModel, onNode: (String) -> Unit) {
    val text by viewModel.text.collectAsStateWithLifecycle(); val result by viewModel.result.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("GLOBAL SEARCH", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); OutlinedTextField(text, viewModel::query, label={Text("Node, ID, message, event")},modifier=Modifier.fillMaxWidth(),singleLine=true) }
        if(text.isBlank()) item { EmptyState("Search SecureMesh", "Find nodes by ID/name, message content and event history.") }
        if(result.nodes.isNotEmpty()) { item{Text("NODES",color=SecureMeshColors.Muted,fontWeight=FontWeight.Bold)}; result.nodes.forEach { n -> item(n.id){TechnicalCard(n.name){Text("NODE ${n.id} · ${n.role}");TextButton({onNode(n.id)}){Text("OPEN")}}} } }
        if(result.messages.isNotEmpty()) { item{Text("MESSAGES",color=SecureMeshColors.Muted,fontWeight=FontWeight.Bold)}; result.messages.forEach { m -> item(m.id){TechnicalCard("${m.origin} → ${m.destination}"){Text(m.payload);Text(m.progressState.name + " · final " + m.finalState.name,color=SecureMeshColors.Muted)}} } }
        if(result.events.isNotEmpty()) { item{Text("EVENTS",color=SecureMeshColors.Muted,fontWeight=FontWeight.Bold)}; result.events.forEach { e -> item(e.id){TechnicalCard(e.title){Text(e.details);Text(e.category.name,color=SecureMeshColors.Muted)}} } }
    }
}
