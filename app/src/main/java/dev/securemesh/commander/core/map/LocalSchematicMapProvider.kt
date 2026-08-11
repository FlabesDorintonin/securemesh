package dev.securemesh.commander.core.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.securemesh.commander.core.ui.SecureMeshColors
import kotlin.math.max

object LocalSchematicMapProvider : MeshMapProvider {
    override val providerName = "Local schematic / MapLibre-ready"
    override val offlineCapable = true

    @Composable
    override fun Render(state: MapRenderState, modifier: Modifier, onNodeSelected: (String) -> Unit) {
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        val positioned = state.nodes.mapNotNull { node -> node.position?.takeIf { it.valid }?.let { node to it } }
        if (positioned.isEmpty()) return
        val minLat = positioned.minOf { it.second.latitude }
        val maxLat = positioned.maxOf { it.second.latitude }
        val minLon = positioned.minOf { it.second.longitude }
        val maxLon = positioned.maxOf { it.second.longitude }
        val latSpan = max(maxLat - minLat, .0008)
        val lonSpan = max(maxLon - minLon, .0008)
        fun point(lat: Double, lon: Double): Offset {
            val x = .10f + ((lon - minLon) / lonSpan).toFloat() * .80f
            val y = .90f - ((lat - minLat) / latSpan).toFloat() * .80f
            return Offset(x * canvasSize.width, y * canvasSize.height)
        }

        Canvas(
            modifier.onSizeChanged { canvasSize = it }.pointerInput(positioned, canvasSize) {
                detectTapGestures { tap ->
                    val nearest = positioned.minByOrNull { entry ->
                        (point(entry.second.latitude, entry.second.longitude) - tap).getDistance()
                    }
                    if (nearest != null) {
                        val distance = (point(nearest.second.latitude, nearest.second.longitude) - tap).getDistance()
                        if (distance < 52f) onNodeSelected(nearest.first.id)
                    }
                }
            }
        ) {
            for (i in 0..8) {
                val x = size.width * i / 8f
                val y = size.height * i / 8f
                drawLine(SecureMeshColors.Muted.copy(alpha = .10f), Offset(x, 0f), Offset(x, size.height), 1f)
                drawLine(SecureMeshColors.Muted.copy(alpha = .10f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            positioned.forEach { (node, pos) ->
                val p = point(pos.latitude, pos.longitude)
                val color = if (node.online) SecureMeshColors.Healthy else SecureMeshColors.Critical
                drawCircle(color.copy(alpha = .15f), 34f, p)
                drawCircle(color, if (node.id == state.selectedNodeId) 19f else 14f, p)
                if (node.id == state.selectedNodeId) drawCircle(SecureMeshColors.Cyan, 28f, p, style = Stroke(3f))
            }
        }
    }
}
