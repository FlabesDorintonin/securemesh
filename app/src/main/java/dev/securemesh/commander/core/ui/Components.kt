package dev.securemesh.commander.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.domain.model.*

@Composable
fun TechnicalCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SecureMeshColors.Surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = SecureMeshColors.Muted, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConnectionBanner(state: MeshConnectionState, modifier: Modifier = Modifier) {
    val (text, color) = when (state) {
        is MeshConnectionState.Connected -> if (state.secureSession == SecureSessionState.ESTABLISHED) "SECURE SESSION" to SecureMeshColors.Healthy else "BLE CONNECTED · SESSION NOT AUTHENTICATED" to SecureMeshColors.Warning
        is MeshConnectionState.Connecting -> "CONNECTING" to SecureMeshColors.Cyan
        is MeshConnectionState.DiscoveringServices -> "DISCOVERING SERVICES" to SecureMeshColors.Cyan
        is MeshConnectionState.IdentifyingSecureMesh -> "IDENTIFYING SECUREMESH" to SecureMeshColors.Cyan
        is MeshConnectionState.SyncingSession -> "SYNCING CAPABILITIES / PERMISSIONS" to SecureMeshColors.Cyan
        is MeshConnectionState.Reconnecting -> "RECONNECTING · ATTEMPT ${state.attempt}" to SecureMeshColors.Warning
        is MeshConnectionState.Error -> "ERROR · ${state.error.userMessage}" to SecureMeshColors.Critical
        MeshConnectionState.BluetoothDisabled -> "BLUETOOTH DISABLED" to SecureMeshColors.Warning
        MeshConnectionState.BluetoothUnavailable -> "BLUETOOTH UNAVAILABLE" to SecureMeshColors.Critical
        is MeshConnectionState.PermissionRequired -> "BLUETOOTH PERMISSION REQUIRED" to SecureMeshColors.Warning
        is MeshConnectionState.Disconnected -> "DISCONNECTED" to SecureMeshColors.Critical
        else -> "OFFLINE" to SecureMeshColors.Muted
    }
    Surface(modifier = modifier.fillMaxWidth(), color = color.copy(alpha = 0.10f)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

fun linkQualityColor(quality: LinkQuality): Color = when (quality) {
    LinkQuality.EXCELLENT, LinkQuality.GOOD -> SecureMeshColors.Healthy
    LinkQuality.DEGRADED -> SecureMeshColors.Warning
    LinkQuality.CRITICAL -> SecureMeshColors.Critical
    LinkQuality.UNKNOWN -> SecureMeshColors.Muted
}


@Composable
fun EmptyState(title: String, detail: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}
