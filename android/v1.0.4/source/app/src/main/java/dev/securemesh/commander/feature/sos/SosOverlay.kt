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
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сигнал SOS", color = SecureMeshColors.Critical, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Узел ${alert.nodeId}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Metric("Время", clockLabel(alert.raisedAtEpochMs))
                Metric("Координаты", alert.position?.let { "${coordinate(it.latitude)}, ${coordinate(it.longitude)}" } ?: "Нет данных")
                Metric("Возраст позиции", alert.position?.let { ageLabel(it.timestampEpochMs) } ?: "Нет данных")
                Metric("Заряд", alert.batteryPercent?.let { "$it%" } ?: "Нет данных")
                Metric("Сеть", localizedTechnicalText(alert.networkStatus))
            }
        },
        confirmButton = {
            Button(
                onClick = onAck,
                enabled = canAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Critical),
            ) {
                Text(if (canAcknowledge) "Подтвердить получение" else "Нет права подтверждения")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canOpenMap) TextButton(onClick = onMap) { Text("Карта") }
                if (canOpenNode) TextButton(onClick = onNode) { Text("Узел") }
            }
        },
    )
}
