package dev.securemesh.commander.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.AuthenticationState
import dev.securemesh.commander.domain.model.MeshConnectionState

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNodes: () -> Unit,
    onMessages: () -> Unit,
    onFieldTest: () -> Unit,
    onEvents: () -> Unit,
    onMore: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val local = state.session?.localNodeIdentity
    val localName = local?.displayName?.let(::deviceDisplayName) ?: "Мой узел"
    val online = remember(state.nodes) { state.nodes.count { it.online } }
    val recentMessages = remember(state.messages) { state.messages.sortedByDescending { it.createdAtEpochMs }.take(4) }
    val secure = state.session?.authenticationState == AuthenticationState.AUTHENTICATED
    val localId = local?.nodeId ?: "ID ещё не получен"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OsScreenHeader(
                title = "Главная",
                subtitle = if (secure) "Защищённая локальная сессия активна" else "SecureMesh operator console",
                trailing = {
                    IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "Ещё", tint = SecureMeshColors.TextSecondary) }
                },
            )
        }

        item {
            OsHeroCard(
                eyebrow = "Локальный узел",
                title = localName,
                subtitle = "${local?.role?.ruLabel() ?: "роль не определена"} · $localId",
                accent = if (secure) SecureMeshColors.Healthy else SecureMeshColors.Warning,
                status = if (secure) "ЗАЩИЩЕНО" else "НЕ ГОТОВО",
            ) { ConnectionBanner(state.connection) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeader("Быстрые действия")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OsActionTile("Сообщение", "Открыть чаты", Icons.Rounded.Chat, SecureMeshColors.Cyan, onMessages, Modifier.weight(1f))
                    OsActionTile(
                        if (state.canOpenNodeList) "Узлы" else "Мой узел",
                        if (state.canOpenNodeList) "$online сейчас в сети" else "Состояние устройства",
                        Icons.Rounded.People,
                        SecureMeshColors.Blue,
                        onNodes,
                        Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OsActionTile("Тест связи", "PDR, RTT и first-hop", Icons.Rounded.Speed, SecureMeshColors.Healthy, onFieldTest, Modifier.weight(1f))
                    OsActionTile("Инструменты", "Маршруты и диагностика", Icons.Rounded.MoreHoriz, SecureMeshColors.Violet, onMore, Modifier.weight(1f))
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeader("Сеть сейчас")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OsStat("Узлы", state.nodes.size.toString(), SecureMeshColors.Cyan, Modifier.weight(1f))
                    OsStat("Онлайн", "$online/${state.nodes.size}", SecureMeshColors.Healthy, Modifier.weight(1f))
                    OsStat("Маршруты", state.routes.size.toString(), SecureMeshColors.Violet, Modifier.weight(1f))
                }
            }
        }

        item {
            Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .75f))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashboardStatusRow("Radio", if (state.connection is MeshConnectionState.Connected) "Связь активна" else "Нет BLE-сессии", if (state.connection is MeshConnectionState.Connected) SecureMeshColors.Healthy else SecureMeshColors.Muted)
                    HorizontalDivider(color = SecureMeshColors.Divider)
                    DashboardStatusRow("Безопасность", state.session?.authenticationState?.ruLabel() ?: "Нет сессии", if (secure) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                    HorizontalDivider(color = SecureMeshColors.Divider)
                    DashboardStatusRow("Топология", "${state.topology.links.size} известных связей", if (state.topology.links.isNotEmpty()) SecureMeshColors.Cyan else SecureMeshColors.Muted)
                }
            }
        }

        item { SectionHeader("Последние сообщения", action = "Все чаты", onAction = onMessages) }
        if (recentMessages.isEmpty()) {
            item { EmptyState("Сообщений пока нет", "Открой «Сообщение», выбери узел и начни локальный диалог.", "Открыть чаты", onMessages) }
        } else {
            items(recentMessages, key = { it.id }) { message ->
                val peerId = if (message.origin == local?.nodeId) message.destination else message.origin
                val peer = state.nodes.firstOrNull { it.id == peerId }
                val peerName = deviceDisplayName(peer?.name ?: peerId)
                PressScaleSurface(onClick = onMessages, modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.SurfaceHigh) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            item { SectionHeader("Последние события", action = "Журнал", onAction = onEvents) }
            if (state.events.isEmpty()) item { Text("Новых событий нет", color = SecureMeshColors.Muted) }
            else items(state.events.take(3), key = { it.id }) { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
                    Surface(Modifier.padding(top = 5.dp).size(8.dp), shape = CircleShape, color = SecureMeshColors.Cyan) {}
                    Column(Modifier.weight(1f)) {
                        Text(localizedTechnicalText(event.title), fontWeight = FontWeight.SemiBold)
                        Text("${clockLabel(event.timestampEpochMs)} · ${event.category.ruLabel()}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun DashboardStatusRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SecureMeshColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Surface(Modifier.size(6.dp), shape = CircleShape, color = color) {}
            Text(value, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}
