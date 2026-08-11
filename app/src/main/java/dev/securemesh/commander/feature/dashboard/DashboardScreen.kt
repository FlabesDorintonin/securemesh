package dev.securemesh.commander.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNodes: () -> Unit,
    onEvents: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val local = state.session?.localNodeIdentity
    val online = state.nodes.count { it.online }
    val activeMessages = state.messages.count {
        it.progressState !in setOf(MessageDeliveryState.DELIVERED, MessageDeliveryState.FAILED, MessageDeliveryState.EXPIRED)
    }
    val gpsFixes = state.nodes.count { it.position?.status(System.currentTimeMillis()) == GpsStatus.FIX }
    val minimumBattery = state.nodes.mapNotNull { it.batteryPercent }.minOrNull()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("SECUREMESH", color = SecureMeshColors.Cyan, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                Text(local?.displayName ?: "LOCAL NODE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(local?.let { "${it.role} · ${it.nodeId}" } ?: "Identity not established", color = SecureMeshColors.Muted)
                ConnectionBanner(state.connection)
                state.demoProfile?.let {
                    StatusChip(it.name, if (it == DemoProfile.CURRENT_FIRMWARE_V05) SecureMeshColors.Warning else SecureMeshColors.Cyan)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TechnicalCard("Visible network", Modifier.weight(1f)) {
                    Metric("Nodes", state.nodes.size.toString())
                    Metric("Online", online.toString())
                }
                TechnicalCard("Traffic", Modifier.weight(1f)) {
                    Metric("Active messages", activeMessages.toString())
                    Metric("Alerts", if (state.sos == null) "0" else "1")
                }
            }
        }
        item {
            Text("OPERATIONAL STATUS", color = SecureMeshColors.Muted, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactStatus("LINKS", state.topology.links.size.toString(), SecureMeshColors.Cyan, Modifier.weight(1f))
                CompactStatus("GPS", if (gpsFixes == 0) "UNAVAILABLE" else "$gpsFixes FIX", if (gpsFixes > 0) SecureMeshColors.Healthy else SecureMeshColors.Muted, Modifier.weight(1f))
                CompactStatus("BATTERY", minimumBattery?.let { "$it% MIN" } ?: "UNKNOWN", SecureMeshColors.Cyan, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactStatus("ROUTES", state.routes.size.toString(), SecureMeshColors.Cyan, Modifier.weight(1f))
                CompactStatus("SESSION", state.session?.authenticationState?.name ?: "NONE", if (state.session?.authenticationState == AuthenticationState.AUTHENTICATED) SecureMeshColors.Healthy else SecureMeshColors.Warning, Modifier.weight(1f))
                CompactStatus("SOS", if (state.sos == null) "CLEAR" else "ACTIVE", if (state.sos == null) SecureMeshColors.Healthy else SecureMeshColors.Critical, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNodes) { Text(if (state.canOpenNodeList) "OPEN NODES" else "OPEN LOCAL NODE") }
                if (state.canOpenEvents) OutlinedButton(onClick = onEvents) { Text("EVENT LOG") }
            }
        }
        if (state.canOpenEvents) {
            item { Text("RECENT VISIBLE EVENTS", color = SecureMeshColors.Muted, fontWeight = FontWeight.Bold) }
            if (state.events.isEmpty()) {
                item { EmptyState("No events", "The authenticated session exposed no events.") }
            } else {
                items(state.events.take(8), key = { it.id }) { event ->
                    TechnicalCard(event.title) {
                        Text("${clockLabel(event.timestampEpochMs)} · ${event.category}", color = SecureMeshColors.Muted)
                        Text(event.details)
                    }
                }
            }
        } else {
            item { Text("System log is not available to this session.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CompactStatus(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = color, fontWeight = FontWeight.Bold)
        }
    }
}
