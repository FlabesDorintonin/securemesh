package dev.securemesh.commander.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val demoProfile = state.demoProfile

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                MeshAvatar(localName, online = state.session != null, size = 54.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("SecureMesh", color = SecureMeshColors.Cyan, style = MaterialTheme.typography.labelLarge)
                    Text(localName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        local?.let { "${it.role.ruLabel()} · ${it.nodeId}" } ?: "Идентичность ещё не установлена",
                        color = SecureMeshColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "Ещё", tint = SecureMeshColors.TextSecondary)
                }
            }
        }

        item { ConnectionBanner(state.connection) }

        if (demoProfile != null) {
            item {
                StatusChip(
                    demoProfile.ruLabel(),
                    if (demoProfile == DemoProfile.CURRENT_FIRMWARE_V05) SecureMeshColors.Warning else SecureMeshColors.Cyan,
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onMessages, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.Chat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Чаты")
                }
                OutlinedButton(onClick = onNodes, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.People, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.canOpenNodeList) "Узлы" else "Мой узел")
                }
            }
        }

        item {
            SectionHeader("Сеть сейчас")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Видимых узлов", state.nodes.size.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
                MetricTile("В сети", "$online/${state.nodes.size}", Modifier.weight(1f), SecureMeshColors.Healthy)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Связей", state.topology.links.size.toString(), Modifier.weight(1f), SecureMeshColors.Blue)
                MetricTile("Маршрутов", state.routes.size.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
            }
        }

        item {
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

        item { SectionHeader("Последние сообщения", action = "Все чаты", onAction = onMessages) }

        if (recentMessages.isEmpty()) {
            item { EmptyState("Сообщений пока нет", "Открой «Чаты» и отправь первое сообщение по сети SecureMesh.") }
        } else {
            items(recentMessages, key = { it.id }) { message ->
                val peerId = if (message.origin == local?.nodeId) message.destination else message.origin
                val peer = state.nodes.firstOrNull { it.id == peerId }
                val peerName = deviceDisplayName(peer?.name ?: peerId)
                Surface(color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.large, onClick = onMessages) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MeshAvatar(peerName, online = peer?.online, size = 44.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.padding(top = 7.dp).size(8.dp)) {
                            Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraSmall, color = SecureMeshColors.Cyan) {}
                        }
                        Column(Modifier.weight(1f)) {
                            Text(localizedTechnicalText(event.title), fontWeight = FontWeight.SemiBold)
                            Text("${clockLabel(event.timestampEpochMs)} · ${event.category.ruLabel()}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun DashboardStatusRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SecureMeshColors.TextSecondary)
        Text(value, color = color, fontWeight = FontWeight.SemiBold)
    }
}
