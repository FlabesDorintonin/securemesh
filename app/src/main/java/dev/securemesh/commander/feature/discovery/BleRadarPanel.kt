package dev.securemesh.commander.feature.discovery

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.SecureMeshColors
import dev.securemesh.commander.core.ui.deviceDisplayName
import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.model.DiscoveredDevice
import kotlin.math.cos
import kotlin.math.sin

internal fun bleProximityLabel(rssi: Int): String = when {
    rssi >= -58 -> "Очень близко"
    rssi >= -70 -> "Рядом"
    rssi >= -82 -> "Недалеко"
    else -> "Слабый сигнал"
}

private fun radiusFactor(rssi: Int): Float = when {
    rssi >= -58 -> .25f
    rssi >= -70 -> .45f
    rssi >= -82 -> .67f
    else -> .87f
}

@Composable
fun BleRadarPanel(devices: List<DiscoveredDevice>, scanning: Boolean) {
    val secure = devices.filter { it.classification != DeviceClassification.UNKNOWN_BLE }.take(12)
    val transition = rememberInfiniteTransition(label = "ble-radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ble-radar-sweep",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .92f),
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .22f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("BLE-радар", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Близость по уровню BLE-сигнала", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = CircleShape, color = SecureMeshColors.Cyan.copy(alpha = .12f)) {
                    Text(if (scanning) "ПОИСК" else "${secure.size} рядом", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelMedium)
                }
            }

            Box(Modifier.fillMaxWidth().height(210.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = minOf(size.width, size.height) * .43f
                    val ringColor = SecureMeshColors.Cyan.copy(alpha = .13f)
                    drawCircle(ringColor, maxRadius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f))
                    drawCircle(ringColor, maxRadius * .67f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f))
                    drawCircle(ringColor, maxRadius * .34f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1f))
                    drawLine(ringColor, Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), 1f)
                    drawLine(ringColor, Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), 1f)

                    val sweepRad = Math.toRadians(sweep.toDouble())
                    drawLine(
                        SecureMeshColors.Cyan.copy(alpha = .52f),
                        center,
                        Offset(center.x + cos(sweepRad).toFloat() * maxRadius, center.y + sin(sweepRad).toFloat() * maxRadius),
                        3f,
                    )
                    drawCircle(SecureMeshColors.CyanHot, 7f, center)

                    secure.forEach { device ->
                        val angle = Math.toRadians(((device.address.hashCode().toLong() and 0x7fffffffL) % 360L).toDouble())
                        val radius = maxRadius * radiusFactor(device.rssi)
                        val point = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
                        drawCircle(SecureMeshColors.Healthy.copy(alpha = .22f), 12f, point)
                        drawCircle(SecureMeshColors.Healthy, 5.5f, point)
                    }
                }
            }

            secure.sortedByDescending { it.rssi }.take(3).forEach { device ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(deviceDisplayName(device.advertisedName), style = MaterialTheme.typography.bodyMedium)
                    Text(bleProximityLabel(device.rssi), color = SecureMeshColors.CyanHot, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Кольца показывают только примерную близость. BLE не определяет направление и точное расстояние.",
                color = SecureMeshColors.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
