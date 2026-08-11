package dev.securemesh.commander.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
    val text by viewModel.text.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("GLOBAL SEARCH", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::query,
                label = { Text("Node, ID, message, event") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        if (text.isBlank()) {
            item { EmptyState("Search SecureMesh", "Find nodes by ID/name, message content and event history.") }
        }

        if (result.nodes.isNotEmpty()) {
            item { Text("NODES", color = SecureMeshColors.Muted, fontWeight = FontWeight.Bold) }
            items(result.nodes, key = { "node-${it.id}" }) { node ->
                TechnicalCard(node.name) {
                    Text("NODE ${node.id} · ${node.role}")
                    TextButton(onClick = { onNode(node.id) }) { Text("OPEN") }
                }
            }
        }

        if (result.messages.isNotEmpty()) {
            item { Text("MESSAGES", color = SecureMeshColors.Muted, fontWeight = FontWeight.Bold) }
            items(result.messages, key = { "message-${it.id}" }) { message ->
                TechnicalCard("${message.origin} → ${message.destination}") {
                    Text(message.payload)
                    Text(
                        "${message.progressState.name} · final ${message.finalState.name}",
                        color = SecureMeshColors.Muted,
                    )
                }
            }
        }

        if (result.events.isNotEmpty()) {
            item { Text("EVENTS", color = SecureMeshColors.Muted, fontWeight = FontWeight.Bold) }
            items(result.events, key = { "event-${it.id}" }) { event ->
                TechnicalCard(event.title) {
                    Text(event.details)
                    Text(event.category.name, color = SecureMeshColors.Muted)
                }
            }
        }
    }
}
