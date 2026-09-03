package dev.securemesh.commander.feature.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.NearbyBleDevice
import dev.securemesh.commander.domain.service.bleProximityLabel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun BleRadarScreen(viewModel: BleRadarViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MeshBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 18.dp, 14.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("BLE Радар узла", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Пассивные BLE-наблюдения, собранные ESP32-S3", color = SecureMeshColors.TextSecondary)
                    }
                    IconButton(onClick = viewModel::clear, enabled = state.supported && !state.clearing) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Очистить радар")
                    }
                }
            }
            if (!state.supported) {
                item { EmptyState("Радар недоступен", "Подключённый узел не сообщает о поддержке BLE-радара.") }
            } else {
                val radar = state.radar
                item { RadarCanvas(radar?.devices.orEmpty(), radar?.scanning == true) }
                item {
                    Surface(
                        color = SecureMeshColors.Warning.copy(alpha = .08f),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .18f)),
                    ) {
                        Text(
                            "Положение точек условное: RSSI позволяет показать только грубую зону близости. Это не измерение расстояния и не направление на устройство.",
                            modifier = Modifier.padding(12.dp),
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    TechnicalCard("Состояние") {
                        Text("Сканер: ${if (radar?.configured == true) "готов" else "не настроен"}")
                        Text("Сейчас: ${if (radar?.scanning == true) "сканирование" else "ожидание"}")
                        Text("Цикл: ${radar?.scanCycle ?: 0} · обнаружений: ${radar?.totalDetections ?: 0}")
                    }
                }
                if (radar?.devices.isNullOrEmpty()) {
                    item { EmptyState("Пока пусто", "После установления защищённой сессии узел периодически выполняет пассивное BLE-сканирование. Дайте ему несколько секунд.") }
                } else {
                    items(radar?.devices.orEmpty(), key = { it.addressHash }) { device -> RadarDeviceRow(device) }
                }
            }
            state.error?.let { message -> item { Text(message, color = SecureMeshColors.Critical, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun RadarDeviceRow(device: NearbyBleDevice) {
    Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Radar, null, tint = SecureMeshColors.CyanHot, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(device.advertisedName?.takeIf { it.isNotBlank() } ?: "BLE ${device.addressHash.toString(16).uppercase()}", fontWeight = FontWeight.SemiBold)
                Text("наблюдений ${device.detections} · возраст ${ageShort(device.ageMs)}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(bleProximityLabel(device.signalDbm), color = SecureMeshColors.CyanHot, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text("${device.signalDbm} dBm", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RadarCanvas(devices: List<NearbyBleDevice>, scanning: Boolean) {
    val infinite = rememberInfiniteTransition(label = "node-radar")
    val rotation by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (scanning) 1700 else 5000, easing = LinearEasing)),
        label = "node-radar-sweep",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(1.35f),
        shape = MaterialTheme.shapes.extraLarge,
        color = SecureMeshColors.SurfaceHigh,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .16f)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val radius = min(size.width, size.height) * .44f
            val center = Offset(size.width / 2f, size.height / 2f)
            repeat(4) { i -> drawCircle(SecureMeshColors.Cyan.copy(alpha = .12f), radius * (i + 1) / 4f, center, style = Stroke(1.dp.toPx())) }
            drawLine(
                SecureMeshColors.CyanHot.copy(alpha = if (scanning) .55f else .22f),
                center,
                Offset(center.x + cos(Math.toRadians(rotation.toDouble())).toFloat() * radius, center.y + sin(Math.toRadians(rotation.toDouble())).toFloat() * radius),
                strokeWidth = 2.dp.toPx(),
            )
            devices.take(10).forEach { d ->
                val normalized = when { d.signalDbm >= -55 -> .22f; d.signalDbm >= -68 -> .42f; d.signalDbm >= -80 -> .63f; d.signalDbm >= -92 -> .82f; else -> .96f }
                val angle = (d.addressHash % 360L).toDouble()
                val pos = Offset(center.x + cos(Math.toRadians(angle)).toFloat() * radius * normalized, center.y + sin(Math.toRadians(angle)).toFloat() * radius * normalized)
                drawCircle(SecureMeshColors.CyanHot, 5.dp.toPx(), pos)
                drawCircle(SecureMeshColors.CyanHot.copy(alpha = .18f), 10.dp.toPx(), pos)
            }
            drawCircle(SecureMeshColors.Healthy, 6.dp.toPx(), center)
        }
    }
}

private fun ageShort(ms: Long): String = when {
    ms < 1_000 -> "сейчас"
    ms < 60_000 -> "${ms / 1_000} с"
    else -> "${ms / 60_000} мин"
}
