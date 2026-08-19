package dev.securemesh.commander.feature.radar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.service.BleProximity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun BleRadarScreen(viewModel: BleRadarViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> viewModel.permissionResult(grants) }

    LaunchedEffect(Unit) { viewModel.startScan() }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BLE-радар", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Поиск устройств только в непосредственной близости", color = SecureMeshColors.TextSecondary)
                }
                OutlinedIconButton(onClick = { viewModel.startScan() }) { Icon(Icons.Rounded.Refresh, contentDescription = "Повторить поиск") }
            }
        }

        item { RadarCanvas(state.devices, state.selectedAddress) }

        item {
            TechnicalCard("Как читать радар") {
                Text("Расстояние оценивается только по силе BLE-сигнала. Угол точки на круге условный и НЕ показывает направление на устройство.", color = SecureMeshColors.TextSecondary)
                Text("Очень близко / Рядом / Недалеко / Далеко — это зоны близости, а не метры.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        state.selected?.let { selected -> item { FocusDeviceCard(selected) { viewModel.select(null) } } }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = { if (state.scanning) viewModel.stopScan() else viewModel.startScan() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.BluetoothSearching, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.scanning) "Остановить" else "Искать")
                }
                if (state.permissionMissing && state.requestedPermissions.isNotEmpty()) {
                    OutlinedButton(onClick = { launcher.launch(state.requestedPermissions.toTypedArray()) }) { Text("Разрешить") }
                }
            }
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = SecureMeshColors.Cyan)
            state.error?.let { Text(it, color = SecureMeshColors.Warning, style = MaterialTheme.typography.bodySmall) }
        }

        item { Text("Рядом", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (state.devices.isEmpty()) {
            item { EmptyState(if (state.scanning) "Идёт поиск" else "Ничего не найдено", "BLE работает на небольшой дистанции. Поднеси телефон ближе и повтори поиск.") }
        } else {
            items(state.devices, key = { it.address }) { device -> RadarDeviceRow(device, selected = device.address == state.selectedAddress) { viewModel.select(device.address) } }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun RadarCanvas(devices: List<RadarDevice>, selectedAddress: String?) {
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "sweep")
    val ringColor = SecureMeshColors.Cyan.copy(alpha = .18f)
    val sweepColor = SecureMeshColors.Cyan.copy(alpha = .68f)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = SecureMeshColors.SurfaceHigh, border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f).padding(14.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = center
                val maxR = size.minDimension * .43f
                repeat(4) { i -> drawCircle(ringColor, maxR * (i + 1) / 4f, center, style = Stroke(width = 1.3f)) }
                drawLine(ringColor, Offset(center.x - maxR, center.y), Offset(center.x + maxR, center.y), 1f)
                drawLine(ringColor, Offset(center.x, center.y - maxR), Offset(center.x, center.y + maxR), 1f)
                val a = phase / 180f * PI
                drawLine(sweepColor, center, Offset(center.x + cos(a).toFloat() * maxR, center.y + sin(a).toFloat() * maxR), 3f, cap = StrokeCap.Round)
                devices.take(12).forEach { device ->
                    val hashAngle = ((device.address.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat() / 180f * PI.toFloat()
                    val normalized = when (device.proximity.proximity) {
                        BleProximity.VERY_CLOSE -> .18f
                        BleProximity.NEAR -> .34f
                        BleProximity.MEDIUM -> .54f
                        BleProximity.FAR -> .76f
                        BleProximity.VERY_FAR -> .94f
                        BleProximity.UNKNOWN -> .88f
                    }
                    val p = Offset(center.x + cos(hashAngle.toDouble()).toFloat() * maxR * normalized, center.y + sin(hashAngle.toDouble()).toFloat() * maxR * normalized)
                    val color = if (device.secureMesh) SecureMeshColors.Healthy else SecureMeshColors.Blue
                    drawCircle(color.copy(alpha = .18f), if (device.address == selectedAddress) 15f else 11f, p)
                    drawCircle(color, if (device.address == selectedAddress) 7f else 5f, p)
                }
            }
            Surface(shape = CircleShape, color = SecureMeshColors.Cyan.copy(alpha = .12f)) {
                Icon(Icons.Rounded.MyLocation, null, tint = SecureMeshColors.CyanHot, modifier = Modifier.padding(12.dp).size(24.dp))
            }
        }
    }
}

@Composable
private fun FocusDeviceCard(device: RadarDevice, close: () -> Unit) {
    val trend = when {
        device.trend >= 3 -> "Сигнал усиливается"
        device.trend <= -3 -> "Сигнал ослабевает"
        else -> "Сигнал примерно стабилен"
    }
    TechnicalCard("Поиск устройства") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: if (device.secureMesh) "SecureMesh" else "BLE-устройство", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(device.proximity.title, color = proximityColor(device.proximity.proximity), fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = close) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
        }
        Text(trend, color = SecureMeshColors.TextSecondary)
        Text("Ориентируйся на изменение сигнала при движении телефона, а не на условное положение точки на круге.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RadarDeviceRow(device: RadarDevice, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) SecureMeshColors.Cyan.copy(alpha = .08f) else SecureMeshColors.Surface,
        border = BorderStroke(1.dp, if (selected) SecureMeshColors.Cyan.copy(alpha = .35f) else SecureMeshColors.Divider.copy(alpha = .55f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MeshAvatar(device.name ?: "BLE", size = 44.dp, accent = if (device.secureMesh) SecureMeshColors.Healthy else SecureMeshColors.Blue)
            Column(Modifier.weight(1f)) {
                Text(device.name ?: if (device.secureMesh) "SecureMesh" else "BLE-устройство", fontWeight = FontWeight.SemiBold)
                Text(if (device.secureMesh) "SecureMesh · ${device.address}" else device.address, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(device.proximity.title, color = proximityColor(device.proximity.proximity), fontWeight = FontWeight.Bold)
                Text("${device.smoothedRssi} dBm", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun proximityColor(value: BleProximity): Color = when (value) {
    BleProximity.VERY_CLOSE, BleProximity.NEAR -> SecureMeshColors.Healthy
    BleProximity.MEDIUM -> SecureMeshColors.CyanHot
    BleProximity.FAR -> SecureMeshColors.Warning
    BleProximity.VERY_FAR -> SecureMeshColors.Critical
    BleProximity.UNKNOWN -> SecureMeshColors.Muted
}
