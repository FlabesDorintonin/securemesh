package dev.securemesh.commander.feature.network

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import kotlin.math.*

@Composable
fun TopologyScreen(viewModel: NetworkViewModel, onNode: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.canTopology) {
        EmptyState("Схема сети недоступна", "Текущая сессия не разрешает просмотр топологии.")
        return
    }

    var selectedNode by remember { mutableStateOf<NodeId?>(null) }
    var selectedLink by remember { mutableStateOf<MeshLink?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val positions = remember(state.topology.nodes, size, zoom, pan, state.localNodeId) {
        layout(state.topology.nodes, state.localNodeId, size, zoom, pan)
    }

    val motion = rememberInfiniteTransition(label = "topology-motion")
    val travel = motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2300, easing = LinearEasing)),
        label = "topology-travel",
    )
    val breathe = motion.animateFloat(
        initialValue = .86f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(1700), repeatMode = RepeatMode.Reverse),
        label = "topology-breathe",
    )

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Живая сеть", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Импульсы идут только по реально наблюдаемым направленным связям.", color = SecureMeshColors.Muted)
            }
            StatusChip("${state.topology.nodes.size} узл.", SecureMeshColors.Cyan)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
                Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("По центру")
            }
            selectedNode?.let { id -> TextButton(onClick = { onNode(id) }) { Text("Открыть узел") } }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = SecureMeshColors.Surface.copy(alpha = .94f),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .14f)),
            shadowElevation = 8.dp,
        ) {
            if (state.topology.nodes.isEmpty()) {
                EmptyState("Сеть пока пуста", "Нет доступных узлов и направленных связей.")
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { size = it }
                        .pointerInput(state.topology) {
                            detectTransformGestures { _, panDelta, zoomDelta, _ ->
                                zoom = (zoom * zoomDelta).coerceIn(.6f, 3f)
                                pan += panDelta
                            }
                        }
                        .pointerInput(positions, state.topology) {
                            detectTapGestures { tap ->
                                val node = positions.minByOrNull { (it.value - tap).getDistance() }
                                if (node != null && (node.value - tap).getDistance() < 50f) {
                                    selectedNode = node.key
                                    selectedLink = null
                                } else {
                                    selectedLink = state.topology.links.minByOrNull { link ->
                                        val a = positions[link.fromNode] ?: return@minByOrNull Float.MAX_VALUE
                                        val b = positions[link.toNode] ?: return@minByOrNull Float.MAX_VALUE
                                        distanceToSegment(tap, a, b)
                                    }?.takeIf { link ->
                                        val a = positions[link.fromNode] ?: return@takeIf false
                                        val b = positions[link.toNode] ?: return@takeIf false
                                        distanceToSegment(tap, a, b) < 35f
                                    }
                                    selectedNode = null
                                }
                            }
                        },
                ) {
                    // Ambient rings are intentionally cheap: translucent primitives only, no blur layer.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(SecureMeshColors.Cyan.copy(alpha = .018f), min(size.width, size.height) * .48f, center)
                    drawCircle(SecureMeshColors.Violet.copy(alpha = .014f), min(size.width, size.height) * .34f, center)

                    state.topology.links.forEachIndexed { index, link ->
                        val a = positions[link.fromNode] ?: return@forEachIndexed
                        val b = positions[link.toNode] ?: return@forEachIndexed
                        val color = linkQualityColor(link.quality())
                        val connectedToSelection = selectedNode == null || link.fromNode == selectedNode || link.toNode == selectedNode
                        val isSelectedLink = selectedLink == link
                        val alpha = when {
                            isSelectedLink -> 1f
                            connectedToSelection -> .82f
                            else -> .16f
                        }
                        val width = if (isSelectedLink) 5.2f * zoom else 3.1f * zoom

                        drawLine(color.copy(alpha = alpha * .12f), a, b, strokeWidth = width * 3f, cap = StrokeCap.Round)
                        drawLine(color.copy(alpha = alpha), a, b, strokeWidth = width, cap = StrokeCap.Round)

                        if (connectedToSelection) {
                            val t = (travel.value + index * .173f) % 1f
                            val pulse = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                            drawCircle(color.copy(alpha = .10f * alpha), 14f * zoom, pulse)
                            drawCircle(color.copy(alpha = .32f * alpha), 7f * zoom, pulse)
                            drawCircle(SecureMeshColors.Text.copy(alpha = .92f * alpha), 2.3f * zoom, pulse)
                        }
                    }

                    positions.forEach { (id, point) ->
                        val node = state.nodes.firstOrNull { it.id == id }
                        val online = node?.online == true
                        val accent = when {
                            id == state.localNodeId -> SecureMeshColors.Cyan
                            online -> SecureMeshColors.Healthy
                            else -> SecureMeshColors.Muted
                        }
                        val selected = id == selectedNode
                        val dimmed = selectedNode != null && !selected
                        val alpha = if (dimmed) .28f else 1f
                        val pulseScale = if (online || id == state.localNodeId) breathe.value else .92f

                        drawCircle(accent.copy(alpha = .05f * alpha), 46f * zoom * pulseScale, point)
                        drawCircle(accent.copy(alpha = .13f * alpha), 31f * zoom, point)
                        drawCircle(SecureMeshColors.GraphiteSoft.copy(alpha = alpha), 22f * zoom, point)
                        drawCircle(accent.copy(alpha = alpha), 15f * zoom, point)
                        drawCircle(SecureMeshColors.Text.copy(alpha = .82f * alpha), 4f * zoom, point)

                        if (id == state.localNodeId) {
                            drawCircle(SecureMeshColors.CyanHot.copy(alpha = .86f * alpha), 29f * zoom, point, style = Stroke(2.8f * zoom))
                        }
                        if (selected) {
                            drawCircle(SecureMeshColors.Warning, 40f * zoom, point, style = Stroke(3f * zoom))
                            drawCircle(SecureMeshColors.Warning.copy(alpha = .10f), 54f * zoom * breathe.value, point)
                        }
                    }
                }
            }
        }

        selectedNode?.let { id ->
            state.nodes.firstOrNull { it.id == id }?.let { node ->
                TechnicalCard("${deviceDisplayName(node.name)} · ${node.id}") {
                    Text("${node.role.ruLabel()} · ${if (node.online) "в сети" else "не в сети"}", color = SecureMeshColors.TextSecondary)
                }
            }
        }

        selectedLink?.let { link ->
            TechnicalCard("${link.fromNode} → ${link.toNode}") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("RSSI", dbm(link.rssi), Modifier.weight(1f), linkQualityColor(link.quality()))
                    Metric("SNR", snr(link.snr), Modifier.weight(1f))
                    Metric("PDR", percent(link.pdr), Modifier.weight(1f))
                }
                Text("Повторы: ${link.retries ?: "—"} · возраст данных: ${link.lastSeenEpochMs?.let(::ageLabel) ?: "—"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun layout(ids: List<NodeId>, local: NodeId?, size: IntSize, zoom: Float, pan: Offset): Map<NodeId, Offset> {
    if (size.width == 0 || ids.isEmpty()) return emptyMap()
    val center = Offset(size.width / 2f, size.height / 2f)
    val ordered = ids.filter { it != local }
    val radius = min(size.width, size.height) * .32f
    val out = mutableMapOf<NodeId, Offset>()
    local?.takeIf { it in ids }?.let { out[it] = center + pan }
    ordered.forEachIndexed { index, id ->
        val angle = -Math.PI / 2 + 2 * Math.PI * index / max(1, ordered.size)
        val raw = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
        out[id] = center + (raw - center) * zoom + pan
    }
    if (local == null) {
        ids.forEachIndexed { index, id ->
            val angle = -Math.PI / 2 + 2 * Math.PI * index / max(1, ids.size)
            val raw = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
            out[id] = center + (raw - center) * zoom + pan
        }
    }
    return out
}

private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val lengthSquared = vx * vx + vy * vy
    if (lengthSquared == 0f) return (point - a).getDistance()
    val t = (((point.x - a.x) * vx + (point.y - a.y) * vy) / lengthSquared).coerceIn(0f, 1f)
    return hypot((point.x - (a.x + t * vx)).toDouble(), (point.y - (a.y + t * vy)).toDouble()).toFloat()
}
