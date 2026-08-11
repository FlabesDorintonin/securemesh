package dev.securemesh.commander.feature.sos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.SosAlert

@Composable
fun SosOverlay(
    alert: SosAlert,
    canOpenMap: Boolean,
    canOpenNode: Boolean,
    canAcknowledge: Boolean,
    onMap: () -> Unit,
    onNode: () -> Unit,
    onAck: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = SecureMeshColors.Surface,
        title = { Text("SOS · NODE ${alert.nodeId}", color = SecureMeshColors.Critical, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Time", clockLabel(alert.raisedAtEpochMs))
                Metric("Location", alert.position?.let { "${coordinate(it.latitude)}, ${coordinate(it.longitude)}" } ?: "UNAVAILABLE")
                Metric("Position age", alert.position?.let { ageLabel(it.timestampEpochMs) } ?: "UNAVAILABLE")
                Metric("Battery", alert.batteryPercent?.let { "$it%" } ?: "UNKNOWN")
                Metric("Network", alert.networkStatus)
            }
        },
        confirmButton = {
            Button(
                onClick = onAck,
                enabled = canAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Critical),
            ) { Text(if (canAcknowledge) "ACKNOWLEDGE" else "ACK NOT PERMITTED") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canOpenMap) TextButton(onClick = onMap) { Text("OPEN MAP") }
                if (canOpenNode) TextButton(onClick = onNode) { Text("OPEN NODE") }
            }
        },
    )
}
