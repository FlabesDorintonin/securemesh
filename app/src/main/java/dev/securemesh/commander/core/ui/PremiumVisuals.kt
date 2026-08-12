package dev.securemesh.commander.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.domain.model.MeshNode
import dev.securemesh.commander.domain.model.MeshTopology
import dev.securemesh.commander.domain.model.NodeId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Premium, truth-preserving summary of the real topology.
 * The graph uses only node/link data already present in the domain model; it never invents peers.
 * Animation state is read inside Canvas so the hot path invalidates draw rather than rebuilding the UI tree.
 */
@Composable
fun PremiumMeshHero(
    localName: String,
    localNodeId: NodeId?,
    nodes: List<MeshNode>,
    topology: MeshTopology,
    secure: Boolean,
    modifier: Modifier = Modifier,
) {
    val online = nodes.count { it.online }
    val total = nodes.size
    val statusColor = if (secure) SecureMeshColors.Healthy else SecureMeshColors.Warning

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .22f)),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            SecureMeshColors.Cyan.copy(alpha = .085f),
                            Color.Transparent,
                            SecureMeshColors.Violet.copy(alpha = .075f),
                        ),
                    ),
                )
                .padding(17.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PremiumStatusDot(active = secure, color = statusColor)
                            Text(
                                if (secure) "СЕТЬ ЗАЩИЩЕНА" else "СЕССИЯ НЕ ПОДТВЕРЖДЕНА",
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Text(localName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text(
                            localNodeId ?: "Локальный узел ещё не определён",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecureMeshColors.Muted,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = SecureMeshColors.Cyan.copy(alpha = .11f),
                        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .20f)),
                    ) {
                        Text(
                            "$online/$total",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = SecureMeshColors.CyanHot,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                PremiumMeshCanvas(
                    nodes = nodes,
                    topology = topology,
                    localNodeId = localNodeId,
                    modifier = Modifier.fillMaxWidth().height(128.dp),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PremiumMetricPill("Узлы", total.toString(), SecureMeshColors.Cyan, Modifier.weight(1f))
                    PremiumMetricPill("Онлайн", online.toString(), SecureMeshColors.Healthy, Modifier.weight(1f))
                    PremiumMetricPill("Связи", topology.links.size.toString(), SecureMeshColors.Blue, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PremiumMetricPill(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = SecureMeshColors.Graphite.copy(alpha = .44f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, accent.copy(alpha = .14f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(120)) },
                label = "premium-metric-$label",
            ) { current ->
                Text(current, color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PremiumStatusDot(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "premium-status")
    val pulse = transition.animateFloat(
        initialValue = .78f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1100), repeatMode = RepeatMode.Reverse),
        label = "premium-status-pulse",
    )
    Canvas(Modifier.size(16.dp)) {
        val scale = if (active) pulse.value else 1f
        drawCircle(color.copy(alpha = if (active) .14f else .08f), radius = size.minDimension * .46f * scale)
        drawCircle(color.copy(alpha = .34f), radius = size.minDimension * .30f)
        drawCircle(color, radius = size.minDimension * .16f)
    }
}

@Composable
fun PremiumMeshCanvas(
    nodes: List<MeshNode>,
    topology: MeshTopology,
    localNodeId: NodeId?,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "premium-mesh")
    val travel = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing)),
        label = "premium-mesh-travel",
    )
    val breathe = transition.animateFloat(
        initialValue = .84f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1800), repeatMode = RepeatMode.Reverse),
        label = "premium-mesh-breathe",
    )

    Canvas(modifier) {
        val ids = buildList {
            localNodeId?.takeIf { it in topology.nodes }?.let(::add)
            topology.nodes.filterNot { it == localNodeId }.forEach(::add)
        }
        if (ids.isEmpty()) return@Canvas

        val center = Offset(size.width * .5f, size.height * .52f)
        val radius = min(size.width, size.height) * .34f
        val positions = mutableMapOf<NodeId, Offset>()

        if (localNodeId != null && localNodeId in ids) {
            positions[localNodeId] = center
            val remotes = ids.filterNot { it == localNodeId }
            remotes.forEachIndexed { index, id ->
                val angle = -PI / 2 + 2 * PI * index / max(1, remotes.size)
                positions[id] = Offset(
                    center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius * .72f,
                )
            }
        } else {
            ids.forEachIndexed { index, id ->
                val angle = -PI / 2 + 2 * PI * index / max(1, ids.size)
                positions[id] = Offset(
                    center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius * .72f,
                )
            }
        }

        // Quiet ambient glow: large translucent circles, no blur layer and no offscreen compositing.
        drawCircle(SecureMeshColors.Cyan.copy(alpha = .025f), radius = size.minDimension * .48f, center = center)
        drawCircle(SecureMeshColors.Violet.copy(alpha = .018f), radius = size.minDimension * .31f, center = Offset(size.width * .76f, size.height * .34f))

        topology.links.forEachIndexed { index, link ->
            val a = positions[link.fromNode] ?: return@forEachIndexed
            val b = positions[link.toNode] ?: return@forEachIndexed
            val color = linkQualityColor(link.quality())
            drawLine(color.copy(alpha = .14f), a, b, strokeWidth = 8.dp.toPx())
            drawLine(color.copy(alpha = .72f), a, b, strokeWidth = 1.7.dp.toPx())

            val t = (travel.value + index * .19f) % 1f
            val p = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            drawCircle(color.copy(alpha = .10f), radius = 10.dp.toPx(), center = p)
            drawCircle(color.copy(alpha = .32f), radius = 5.dp.toPx(), center = p)
            drawCircle(SecureMeshColors.Text.copy(alpha = .92f), radius = 1.7.dp.toPx(), center = p)
        }

        positions.forEach { (id, point) ->
            val node = nodes.firstOrNull { it.id == id }
            val online = node?.online == true
            val accent = when {
                id == localNodeId -> SecureMeshColors.Cyan
                online -> SecureMeshColors.Healthy
                else -> SecureMeshColors.Muted
            }
            val nodeScale = if (online || id == localNodeId) breathe.value else .92f
            drawCircle(accent.copy(alpha = .055f), radius = 25.dp.toPx() * nodeScale, center = point)
            drawCircle(accent.copy(alpha = .15f), radius = 16.dp.toPx(), center = point)
            drawCircle(SecureMeshColors.GraphiteSoft, radius = 9.dp.toPx(), center = point)
            drawCircle(accent, radius = 6.2.dp.toPx(), center = point)
            drawCircle(SecureMeshColors.Text.copy(alpha = .82f), radius = 1.8.dp.toPx(), center = point)
            if (id == localNodeId) {
                drawCircle(SecureMeshColors.CyanHot.copy(alpha = .72f), radius = 12.dp.toPx(), center = point, style = Stroke(1.3.dp.toPx()))
            }
        }
    }
}
