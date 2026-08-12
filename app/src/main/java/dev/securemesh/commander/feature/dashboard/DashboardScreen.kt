package dev.securemesh.commander.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    onDeviceUi: () -> Unit,
    onMore: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val local = state.session?.localNodeIdentity
    val localName = local?.displayName?.let(::deviceDisplayName) ?: "Мой узел"
    val online = state.nodes.count { it.online }
    val gpsFixes = state.nodes.count { it.position?.status(System.currentTimeMillis()) == GpsStatus.FIX }
    val minimumBattery = state.nodes.mapNotNull { it.batteryPercent }.minOrNull()
    val recentMessages = state.messages.sortedByDescending { it.createdAtEpochMs }.take(4)
    val oledAvailable = state.session?.supports(DeviceCapability.UI_OS) == true
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            StaggeredReveal(entered, 0) {
                CommandCenterHero(
                    localName = localName,
                    local = local,
                    secure = state.session?.authenticationState == AuthenticationState.AUTHENTICATED,
                    online = online,
                    totalNodes = state.nodes.size,
                    onMore = onMore,
                )
            }
        }

        item {
            StaggeredReveal(entered, 45) { ConnectionBanner(state.connection) }
        }

        if (oledAvailable) {
            item {
                StaggeredReveal(entered, 85) {
                    OledQuickControl(
                        firmware = state.session?.firmwareVersion ?: "0.6.3",
                        nodeName = localName,
                        onClick = onDeviceUi,
                    )
                }
            }
        }

        item {
            StaggeredReveal(entered, 120) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CommandActionCard(
                        title = "Чаты",
                        subtitle = if (recentMessages.isEmpty()) "Начать переписку" else "${recentMessages.size} недавних",
                        icon = Icons.Rounded.Chat,
                        accent = SecureMeshColors.Cyan,
                        modifier = Modifier.weight(1f),
                        onClick = onMessages,
                    )
                    CommandActionCard(
                        title = if (state.canOpenNodeList) "Узлы" else "Мой узел",
                        subtitle = if (state.canOpenNodeList) "$online в сети" else "Статус устройства",
                        icon = Icons.Rounded.People,
                        accent = SecureMeshColors.Blue,
                        modifier = Modifier.weight(1f),
                        onClick = onNodes,
                    )
                }
            }
        }

        item {
            StaggeredReveal(entered, 155) {
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
            StaggeredReveal(entered, 190) {
                TechnicalCard("Готовность системы") {
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
                    DashboardStatusRow("OLED UI", if (oledAvailable) "Доступен" else "Не объявлен", if (oledAvailable) SecureMeshColors.CyanHot else SecureMeshColors.Muted)
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
private fun CommandCenterHero(
    localName: String,
    local: NodeIdentity?,
    secure: Boolean,
    online: Int,
    totalNodes: Int,
    onMore: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        SecureMeshColors.Cyan.copy(alpha = .17f),
                        SecureMeshColors.SurfaceHigh.copy(alpha = .96f),
                        SecureMeshColors.Violet.copy(alpha = .12f),
                    ),
                ),
            )
            .border(1.dp, SecureMeshColors.Cyan.copy(alpha = .23f), RoundedCornerShape(30.dp))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(if (secure) "SECURE" else "LOCAL", if (secure) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                        Text("COMMAND CENTER", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(localName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text(
                        local?.let { "${it.role.ruLabel()} · ${it.nodeId}" } ?: "Локальный узел ещё не определён",
                        color = SecureMeshColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "Ещё", tint = SecureMeshColors.TextSecondary)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroMetric("Сеть", "$online/$totalNodes", SecureMeshColors.Healthy, Modifier.weight(1f))
                HeroMetric("BLE", if (secure) "OK" else "WAIT", SecureMeshColors.CyanHot, Modifier.weight(1f))
                HeroMetric("Режим", "LIVE", SecureMeshColors.Violet, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = .18f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = .17f)),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun OledQuickControl(firmware: String, nodeName: String, onClick: () -> Unit) {
    PressScaleSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .94f),
        border = BorderStroke(1.dp, SecureMeshColors.CyanHot.copy(alpha = .30f)),
        shape = RoundedCornerShape(26.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(SecureMeshColors.Cyan.copy(alpha = .24f), SecureMeshColors.Blue.copy(alpha = .16f))))
                    .border(1.dp, SecureMeshColors.Cyan.copy(alpha = .30f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = SecureMeshColors.CyanHot, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("OLED-пульт", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    StatusChip("LIVE", SecureMeshColors.Healthy)
                }
                Text("$nodeName · firmware $firmware", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Открыть экран и управлять меню узла", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelMedium)
            }
            Text("›", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun CommandActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PressScaleSurface(
        onClick = onClick,
        modifier = modifier.height(102.dp),
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
        border = BorderStroke(1.dp, accent.copy(alpha = .22f)),
        shape = RoundedCornerShape(23.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(34.dp).background(accent.copy(alpha = .13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
