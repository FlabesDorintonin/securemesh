package dev.securemesh.commander.core.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.SecureMeshColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object LocalSchematicMapProvider : MeshMapProvider {
    override val providerName = "Локальная тактическая карта"
    override val offlineCapable = true

    @Composable
    override fun Render(state: MapRenderState, modifier: Modifier, onPointSelected: (String) -> Unit) {
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        var centerLat by remember { mutableDoubleStateOf(0.0) }
        var centerLon by remember { mutableDoubleStateOf(0.0) }
        var zoom by remember { mutableFloatStateOf(1.8f) }

        fun visibleLonSpan(): Double = 360.0 / 2.0.pow(zoom.toDouble())
        fun visibleLatSpan(): Double = 170.0 / 2.0.pow(zoom.toDouble())

        fun fitAll() {
            val points = state.points.filter { validCoordinate(it.latitude, it.longitude) }
            if (points.isEmpty()) {
                centerLat = 0.0
                centerLon = 0.0
                zoom = 1.8f
                return
            }
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            centerLat = ((minLat + maxLat) / 2.0).coerceIn(-85.0, 85.0)
            centerLon = wrapLongitude((minLon + maxLon) / 2.0)
            if (points.size == 1) {
                zoom = 13f
            } else {
                val latSpan = max(maxLat - minLat, .0005)
                val lonSpan = max(maxLon - minLon, .0005)
                val zLon = log2(300.0 / lonSpan)
                val zLat = log2(135.0 / latSpan)
                zoom = min(zLon, zLat).toFloat().coerceIn(2f, 16f)
            }
        }

        fun pointOffset(latitude: Double, longitude: Double): Offset {
            if (canvasSize.width == 0 || canvasSize.height == 0) return Offset.Zero
            val lonSpan = visibleLonSpan()
            val latSpan = visibleLatSpan()
            val lonDelta = shortestLongitudeDelta(longitude, centerLon)
            val x = canvasSize.width / 2f + (lonDelta / lonSpan * canvasSize.width).toFloat()
            val y = canvasSize.height / 2f - ((latitude - centerLat) / latSpan * canvasSize.height).toFloat()
            return Offset(x, y)
        }

        LaunchedEffect(state.fitAllRequest, state.points.map { Triple(it.id, it.latitude, it.longitude) }) {
            fitAll()
        }

        LaunchedEffect(state.followSelected, state.selectedPointId, state.points) {
            if (state.followSelected) {
                state.points.firstOrNull { it.id == state.selectedPointId }?.let { point ->
                    centerLat = point.latitude.coerceIn(-85.0, 85.0)
                    centerLon = wrapLongitude(point.longitude)
                    zoom = max(zoom, 13f)
                }
            }
        }

        Box(modifier) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(state.points, canvasSize, centerLat, centerLon, zoom) {
                        detectTapGestures { tap ->
                            val nearest = state.points
                                .filter { validCoordinate(it.latitude, it.longitude) }
                                .minByOrNull { point -> (pointOffset(point.latitude, point.longitude) - tap).getDistance() }
                            if (nearest != null && (pointOffset(nearest.latitude, nearest.longitude) - tap).getDistance() < 64f) {
                                onPointSelected(nearest.id)
                            }
                        }
                    }
                    .pointerInput(centerLat, centerLon, zoom) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val lonSpan = visibleLonSpan()
                            val latSpan = visibleLatSpan()
                            if (size.width > 0 && size.height > 0) {
                                centerLon = wrapLongitude(centerLon - pan.x / size.width * lonSpan)
                                centerLat = (centerLat + pan.y / size.height * latSpan).coerceIn(-85.0, 85.0)
                            }
                            if (zoomChange.isFinite() && zoomChange > 0f) {
                                zoom = (zoom + log2(zoomChange.toDouble()).toFloat()).coerceIn(1f, 18f)
                            }
                        }
                    },
            ) {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF071A25),
                            Color(0xFF07131D),
                            Color(0xFF0A1624),
                        ),
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        listOf(
                            SecureMeshColors.Cyan.copy(alpha = .075f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * .78f, size.height * .22f),
                        radius = size.maxDimension * .72f,
                    ),
                )

                val lonSpan = visibleLonSpan()
                val latSpan = visibleLatSpan()
                val step = gridStep(zoom)
                val leftLon = centerLon - lonSpan / 2.0
                val rightLon = centerLon + lonSpan / 2.0
                val bottomLat = centerLat - latSpan / 2.0
                val topLat = centerLat + latSpan / 2.0

                var lon = floor(leftLon / step) * step
                var guard = 0
                while (lon <= rightLon + step && guard++ < 80) {
                    val x = size.width / 2f + ((lon - centerLon) / lonSpan * size.width).toFloat()
                    val major = abs((lon / step).toLong() % 5L) == 0L
                    drawLine(
                        color = SecureMeshColors.Cyan.copy(alpha = if (major) .14f else .065f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = if (major) 1.5f else 1f,
                    )
                    lon += step
                }

                var lat = floor(bottomLat / step) * step
                guard = 0
                while (lat <= topLat + step && guard++ < 80) {
                    val y = size.height / 2f - ((lat - centerLat) / latSpan * size.height).toFloat()
                    val major = abs((lat / step).toLong() % 5L) == 0L
                    drawLine(
                        color = SecureMeshColors.Blue.copy(alpha = if (major) .14f else .065f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = if (major) 1.5f else 1f,
                    )
                    lat += step
                }

                drawLine(
                    SecureMeshColors.Text.copy(alpha = .15f),
                    Offset(size.width / 2f - 12f, size.height / 2f),
                    Offset(size.width / 2f + 12f, size.height / 2f),
                    1.5f,
                )
                drawLine(
                    SecureMeshColors.Text.copy(alpha = .15f),
                    Offset(size.width / 2f, size.height / 2f - 12f),
                    Offset(size.width / 2f, size.height / 2f + 12f),
                    1.5f,
                )

                state.points.filter { validCoordinate(it.latitude, it.longitude) }.forEach { point ->
                    val p = pointOffset(point.latitude, point.longitude)
                    if (p.x < -80f || p.x > size.width + 80f || p.y < -80f || p.y > size.height + 80f) return@forEach
                    val color = when (point.kind) {
                        MapPointKind.SOS -> SecureMeshColors.Critical
                        MapPointKind.WAYPOINT -> SecureMeshColors.Warning
                        MapPointKind.NODE -> when (point.online) {
                            true -> SecureMeshColors.Healthy
                            false -> SecureMeshColors.Muted
                            null -> SecureMeshColors.Cyan
                        }
                    }
                    val selected = point.id == state.selectedPointId
                    drawCircle(color.copy(alpha = .10f), if (selected) 42f else 32f, p)
                    drawCircle(color.copy(alpha = .22f), if (selected) 29f else 23f, p)
                    drawCircle(color, if (selected) 13f else 10f, p)
                    drawCircle(SecureMeshColors.Graphite, if (selected) 6f else 4.5f, p)
                    if (selected) {
                        drawCircle(SecureMeshColors.Cyan, 34f, p, style = Stroke(2.5f))
                    }
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .22f)),
            ) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("ОФЛАЙН · КООРДИНАТНАЯ КАРТА", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("${formatCoordinate(centerLat)} · ${formatCoordinate(centerLon)}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }

            Column(
                Modifier.align(Alignment.CenterEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MapControlButton("+") { zoom = (zoom + 1f).coerceAtMost(18f) }
                MapControlButton("−") { zoom = (zoom - 1f).coerceAtLeast(1f) }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                color = SecureMeshColors.SurfaceHigh.copy(alpha = .86f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(42.dp).height(2.dp).padding(0.dp)) {
                        Surface(Modifier.fillMaxSize(), color = SecureMeshColors.Cyan) {}
                    }
                    Text("≈ ${scaleLabel(centerLat, visibleLonSpan())}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                shape = CircleShape,
                color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
                border = BorderStroke(1.dp, SecureMeshColors.Divider),
            ) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Text("N", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun MapControlButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .92f),
        border = BorderStroke(1.dp, SecureMeshColors.Divider),
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = SecureMeshColors.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private fun gridStep(zoom: Float): Double = when {
    zoom < 2f -> 30.0
    zoom < 4f -> 10.0
    zoom < 6f -> 2.0
    zoom < 8f -> .5
    zoom < 10f -> .1
    zoom < 12f -> .02
    zoom < 14f -> .005
    zoom < 16f -> .001
    else -> .0002
}

private fun validCoordinate(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

private fun wrapLongitude(value: Double): Double {
    var out = value
    while (out > 180.0) out -= 360.0
    while (out < -180.0) out += 360.0
    return out
}

private fun shortestLongitudeDelta(value: Double, center: Double): Double {
    var delta = value - center
    while (delta > 180.0) delta -= 360.0
    while (delta < -180.0) delta += 360.0
    return delta
}

private fun formatCoordinate(value: Double): String = String.format(java.util.Locale.US, "%.5f°", value)

private fun scaleLabel(latitude: Double, visibleLonSpan: Double): String {
    val kmAcross = visibleLonSpan * 111.32 * max(.15, cos(Math.toRadians(latitude)))
    val quarter = max(.001, kmAcross / 4.0)
    return when {
        quarter >= 100.0 -> "${(quarter / 100.0).toInt() * 100} км"
        quarter >= 10.0 -> "${(quarter / 10.0).toInt() * 10} км"
        quarter >= 1.0 -> "${quarter.toInt().coerceAtLeast(1)} км"
        quarter >= .1 -> "${(quarter * 10).toInt().coerceAtLeast(1) * 100} м"
        else -> "${(quarter * 1000).toInt().coerceAtLeast(1)} м"
    }
}
