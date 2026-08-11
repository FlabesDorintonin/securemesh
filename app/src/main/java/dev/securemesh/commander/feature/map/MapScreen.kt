package dev.securemesh.commander.feature.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.map.*
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.feature.network.NetworkViewModel

@Composable
fun MapScreen(
    viewModel: NetworkViewModel,
    provider: MeshMapProvider = LocalSchematicMapProvider,
    onNode: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(false) }
    var fitAllRequest by remember { mutableIntStateOf(0) }

    val positionedNodes = if (state.canMap) {
        state.mapNodes.filter { node -> node.position?.valid == true }
    } else {
        emptyList()
    }
    val points = positionedNodes.mapNotNull { node ->
        node.position?.let { position ->
            MapPoint(
                id = node.id,
                label = deviceDisplayName(node.name),
                latitude = position.latitude,
                longitude = position.longitude,
                kind = MapPointKind.NODE,
                online = node.online,
            )
        }
    }

    LaunchedEffect(points.map { it.id }) {
        if (selected != null && points.none { it.id == selected }) {
            selected = null
            follow = false
        }
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Карта", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Локальная географическая сцена для узлов, точек и будущих GPS-координат",
                        color = SecureMeshColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                StatusChip("ОФЛАЙН", SecureMeshColors.Healthy)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    if (points.isEmpty()) "Точек пока нет" else "Точек: ${points.size}",
                    if (points.isEmpty()) SecureMeshColors.Warning else SecureMeshColors.Cyan,
                )
                Text(provider.providerName, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        selected = null
                        follow = false
                        fitAllRequest++
                    },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .26f)),
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (points.isEmpty()) "Обзор карты" else "Вся сеть")
                }
                FilterChip(
                    selected = follow,
                    onClick = { follow = !follow },
                    label = { Text("Следовать") },
                    leadingIcon = { Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    enabled = selected != null,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = SecureMeshColors.Surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f)),
                tonalElevation = 2.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    provider.Render(
                        state = MapRenderState(
                            points = points,
                            selectedPointId = selected,
                            followSelected = follow,
                            fitAllRequest = fitAllRequest,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) { selected = it }

                    if (points.isEmpty()) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 52.dp),
                            color = SecureMeshColors.SurfaceHigh.copy(alpha = .90f),
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .24f)),
                        ) {
                            Column(
                                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text("Карта уже работает", fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
                                Text(
                                    if (state.canMap) {
                                        "Как только узел передаст GPS-координаты, точка появится здесь автоматически."
                                    } else {
                                        "Базовая карта доступна. Координаты узлов появятся, когда прошивка и сессия разрешат GPS-позиции."
                                    },
                                    color = SecureMeshColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = selected != null) {
                selected?.let { id ->
                    positionedNodes.firstOrNull { it.id == id }?.let { node ->
                        val position = node.position ?: return@let
                        Surface(
                            color = SecureMeshColors.SurfaceHigh,
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .24f)),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MeshAvatar(deviceDisplayName(node.name), node.online, size = 46.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(deviceDisplayName(node.name), fontWeight = FontWeight.Bold)
                                    Text(
                                        "${coordinate(position.latitude)}, ${coordinate(position.longitude)}",
                                        color = SecureMeshColors.CyanHot,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "GPS: ${position.status(System.currentTimeMillis()).ruLabel()} · спутники ${position.satellites ?: "—"} · HDOP ${position.hdop ?: "—"}",
                                        color = SecureMeshColors.Muted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                TextButton(onClick = { onNode(id) }) { Text("Открыть") }
                            }
                        }
                    }
                }
            }
        }
    }
}
