package dev.securemesh.commander.feature.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun MessagesScreen(viewModel: MessagesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    if (!state.canView) {
        EmptyState("Messages unavailable", "VIEW_MESSAGES was not granted.")
        return
    }

    val remotes = state.nodes.filter { it.id != state.localNodeId }
    var target by remember(remotes) { mutableStateOf(remotes.firstOrNull()?.id.orEmpty()) }
    var text by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MeshMessage?>(null) }
    val selectedMessage = selected
    val conversation = state.messages.filter { it.destination == target || it.origin == target }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("MESSAGES", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Hop ACK progress is distinct from end-to-end delivery.", color = SecureMeshColors.Muted)
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(remotes, key = { it.id }) { node ->
                    FilterChip(
                        selected = target == node.id,
                        onClick = { target = node.id },
                        label = { Text(node.name) },
                    )
                }
            }
        }

        item {
            TechnicalCard("Direct conversation") {
                NodeSelector(target, remotes) { target = it }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.send(target, text)
                        text = ""
                    },
                    enabled = state.canSend && target.isNotBlank() && text.isNotBlank(),
                ) {
                    Text("SEND")
                }
                if (!state.canSend) {
                    Text("SEND_MESSAGE not granted", color = SecureMeshColors.Warning)
                }
                error?.let { message ->
                    Text(message, color = SecureMeshColors.Critical)
                }
            }
        }

        if (conversation.isEmpty()) {
            item {
                EmptyState("No messages", "No authorized messages in this direct conversation.")
            }
        } else {
            items(conversation, key = { it.id }) { message ->
                MessageCard(message) { selected = message }
            }
        }

        if (selectedMessage != null) {
            item(key = "message-details-${selectedMessage.id}") {
                MessageDetails(selectedMessage) { selected = null }
            }
        }
    }
}

@Composable
private fun NodeSelector(selected: String, nodes: List<MeshNode>, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Destination: ${nodes.firstOrNull { it.id == selected }?.name ?: "Select"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = { Text("${node.name} · ${node.id}") },
                    onClick = {
                        set(node.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: MeshMessage, open: () -> Unit) {
    val color = when (message.finalState) {
        MessageFinalState.DELIVERED -> SecureMeshColors.Healthy
        MessageFinalState.FAILED, MessageFinalState.EXPIRED -> SecureMeshColors.Critical
        MessageFinalState.UNKNOWN -> SecureMeshColors.Warning
        else -> SecureMeshColors.Cyan
    }

    TechnicalCard("${message.origin} → ${message.destination}") {
        Text(message.payload)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusChip(message.progressState.name, color)
            Text("Final: ${message.finalState}", color = SecureMeshColors.Muted)
        }
        if (message.hopTrace.isNotEmpty()) {
            Text("Observed ${message.observedRoute().joinToString(" → ")}", color = SecureMeshColors.Muted)
        }
        TextButton(onClick = open) { Text("DETAILS") }
    }
}

@Composable
private fun MessageDetails(message: MeshMessage, close: () -> Unit) {
    TechnicalCard("MESSAGE ${message.id}") {
        Metric("Origin", message.origin)
        Metric("Destination", message.destination)
        Metric("Progress", message.progressState.name)
        Metric("Final state", message.finalState.name)
        Text("Hop trace", fontWeight = FontWeight.Bold)
        if (message.hopTrace.isEmpty()) {
            Text("UNAVAILABLE", color = SecureMeshColors.Muted)
        } else {
            message.hopTrace.forEach { hop ->
                Text(
                    "${hop.from} → ${hop.to} · ${hop.ackState} · retries ${hop.retries ?: "UNKNOWN"} · RSSI ${dbm(hop.rssi)} · SNR ${snr(hop.snr)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val deliveryTime = message.deliveryTimeMs()?.let { "${it}ms" } ?: "UNKNOWN"
        Text("Delivery time $deliveryTime", color = SecureMeshColors.Muted)
        TextButton(onClick = close) { Text("CLOSE") }
    }
}
