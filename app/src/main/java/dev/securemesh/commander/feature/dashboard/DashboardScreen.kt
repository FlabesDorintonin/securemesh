package dev.securemesh.commander.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNodes: () -> Unit,
    onMessages: () -> Unit,
    onEvents: () -> Unit,
    onMore: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val local = state.session?.localNodeIdentity
    val localName = local?.displayName?.let(::deviceDisplayName) ?: "Мой узел"
    val online = state.nodes.count { it.online }
    val gpsFixes = state.nodes.count { it.position?.status(System.currentTimeMillis()) == GpsStatus.FIX }
    val minimumBattery = state.nodes.mapNotNull { it.batteryPercent }.minOrNull()
    val recentMessages = state.messages.sortedByDescending { it.createdAtEpochMs }.take(4)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StaggeredReveal(entered, 0) {
                Surface(
                    color = SecureMeshColors.SurfaceHigh.copy(alpha = .82f),
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .17f)),
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        MeshAvatar(localName, online = state.session != null, size = 56.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("SECUREMESH", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                                Surface(Modifier.size(6.dp), shape = CircleShape, color = if (state.session != null) SecureMeshColors.Healthy else SecureMeshColors.Muted) {}
                            }
                            Text(localName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            Text(
                                local?.let { "${it.role.ruLabel()} · ${it.nodeId}" } ?: "Локальный узел ещё не определён",
                                color = SecureMeshColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = onMore) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Ещё", tint = SecureMeshColors.TextSecondary)
                        }
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 45) { ConnectionBanner(state.connection) }
        }

        item {
            StaggeredReveal(entered, 105) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PressScaleSurface(
                        onClick = onMessages,
                        modifier = Modifier.weight(1f).height(86.dp),
                        color = SecureMeshColors.Cyan.copy(alpha = .13f),
                        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .26f)),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Icon(Icons.Rounded.Chat, contentDescription = null, tint = SecureMeshColors.CyanHot, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Чаты", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("Открыть переписки", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    PressScaleSurface(
                        onClick = onNodes,
                        modifier = Modifier.weight(1f).height(86.dp),
                        color = SecureMeshColors.Blue.copy(alpha = .11f),
                        border = BorderStroke(1.dp, SecureMeshColors.Blue.copy(alpha = .24f)),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Icon(Icons.Rounded.People, contentDescription = null, tint = SecureMeshColors.Blue, modifier = Modifier.size(24.dp))
                            Column {
                                Text(if (state.canOpenNodeList) "Узлы" else "Мой узел", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(if (state.canOpenNodeList) "$online в сети" else "Состояние устройства", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 145) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SectionHeader("Сеть сейчас")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MetricTile("Узлов", state.nodes.size.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
                        MetricTile("Онлайн", "$online/${state.nodes.size}", Modifier.weight(1f), SecureMeshColors.Healthy)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MetricTile("Связей", state.topology.links.size.toString(), Modifier.weight(1f), SecureMeshColors.Blue)
                        MetricTile("Маршрутов", state.routes.size.toString(), Modifier.weight(1f), SecureMeshColors.Violet)
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 185) {
                TechnicalCard("Состояние локальной сети") {
                    DashboardStatusRow("GPS", if (gpsFixes > 0) "$gpsFixes узл. с фиксацией" else "Нет данных", if (gpsFixes > 0) SecureMeshColors.Healthy else SecureMeshColors.Muted)
                    HorizontalDivider(color = SecureMeshColors.Divider)
                    DashboardStatusRow("Питание", minimumBattery?.let { "Минимум $it%" } ?: "Нет данных", if (minimumBattery != null) SecureMeshColors.Cyan else SecureMeshColors.Muted)
                    HorizontalDivider(color = SecureMeshColors.Divider)
                    DashboardStatusRow(
                        "Сессия",
                        state.session?.authenticationState.ruLabel(),
                        if (state.session?.authenticationState == AuthenticationState.AUTHENTICATED) SecureMeshColors.Healthy else SecureMeshColors.Warning,
                    )
                    HorizontalDivider(color = SecureMeshColors.Divider)
                    DashboardStatusRow("SOS", if (state.sos == null) "Спокойно" else "Активный сигнал", if (state.sos == null) SecureMeshColors.Healthy else SecureMeshColors.Critical)
                }
            }
        }

        item { SectionHeader("Последние сообщения", action = "Все чаты", onAction = onMessages) }

        if (recentMessages.isEmpty()) {
            item {
                Surface(
                    color = SecureMeshColors.SurfaceHigh.copy(alpha = .70f),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .70f)),
                ) {
                    Text(
                        "Здесь появятся последние переписки. Открой «Чаты», чтобы начать диалог.",
                        modifier = Modifier.padding(16.dp),
                        color = SecureMeshColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(recentMessages, key = { it.id }) { message ->
                val peerId = if (message.origin == local?.nodeId) message.destination else message.origin
                val peer = state.nodes.firstOrNull { it.id == peerId }
                val peerName = deviceDisplayName(peer?.name ?: peerId)
                PressScaleSurface(
                    onClick = onMessages,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    color = SecureMeshColors.SurfaceHigh.copy(alpha = .82f),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MeshAvatar(peerName, online = peer?.online, size = 46.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(peerName, fontWeight = FontWeight.SemiBold)
                                Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
                            }
                            Text(message.payload, color = SecureMeshColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (state.canOpenEvents) {
            item { SectionHeader("События", action = "Журнал", onAction = onEvents) }
            if (state.events.isEmpty()) {
                item { Text("Новых событий нет", color = SecureMeshColors.Muted) }
            } else {
                items(state.events.take(4), key = { it.id }) { event ->
                    Row(
                        Modifier.fillMaxWidth().animateContentSize().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(Modifier.padding(top = 5.dp).size(9.dp), shape = CircleShape, color = SecureMeshColors.Cyan) {}
                        Column(Modifier.weight(1f)) {
                            Text(localizedTechnicalText(event.title), fontWeight = FontWeight.SemiBold)
                            Text("${clockLabel(event.timestampEpochMs)} · ${event.category.ruLabel()}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun DashboardStatusRow(label: String, value: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SecureMeshColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Surface(Modifier.size(6.dp), shape = CircleShape, color = color) {}
            Text(value, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}
