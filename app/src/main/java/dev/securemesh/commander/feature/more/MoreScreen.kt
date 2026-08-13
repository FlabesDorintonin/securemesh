package dev.securemesh.commander.feature.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.SecureMeshSession
import dev.securemesh.commander.domain.service.UiAccessPolicy

private data class MoreDestination(
    val title: String,
    val description: String,
    val route: String,
    val icon: ImageVector,
    val accent: Color,
)

@Composable
fun MoreScreen(session: SecureMeshSession?, open: (String) -> Unit) {
    val canMapPoints = UiAccessPolicy.canShowMap(session)
    val networkItems = buildList {
        if (UiAccessPolicy.canShowTopology(session)) {
            add(MoreDestination("Схема сети", "Кто с кем связан и в каком направлении идёт радио-линк", "topology", Icons.Rounded.Hub, SecureMeshColors.Cyan))
        }
        if (UiAccessPolicy.canShowRoutes(session)) {
            add(MoreDestination("Маршруты", "Куда пойдёт пакет и через какой следующий узел", "routes", Icons.Rounded.AltRoute, SecureMeshColors.Blue))
        }
    }
    val toolItems = buildList {
        if (session?.supports(dev.securemesh.commander.domain.model.DeviceCapability.VANGUARD) == true) {
            add(MoreDestination("VANGUARD Control", "Manifest, Primary/G2, discovery и Fault Lab", "vanguard", Icons.Rounded.Tune, SecureMeshColors.Cyan))
        }
        if (UiAccessPolicy.canControlDeviceUi(session)) {
            add(MoreDestination("Экран узла", "Живой UI state и пульт физического OLED", "devicecontrol", Icons.Rounded.Smartphone, SecureMeshColors.Blue))
        }
        if (UiAccessPolicy.canRunFieldTest(session)) {
            add(MoreDestination("Полевой тест", "Проверка реальной связи, RSSI, SNR, PDR и повторов", "fieldtest", Icons.Rounded.Science, SecureMeshColors.Violet))
        }
        if (UiAccessPolicy.canShowSystemLog(session)) {
            add(MoreDestination("События", "Локальная лента изменений, тревог и действий сети", "events", Icons.Rounded.Notifications, SecureMeshColors.Warning))
        }
        if (UiAccessPolicy.canShowDiagnostics(session)) {
            add(MoreDestination("Диагностика", "Состояние приложения, Bluetooth и mesh-компонентов", "diagnostics", Icons.Rounded.BugReport, SecureMeshColors.Healthy))
        }
    }
    val systemItems = listOf(
        MoreDestination("Настройки", "Безопасность, Bluetooth и локальные данные", "settings", Icons.Rounded.Settings, SecureMeshColors.TextSecondary),
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    MeshBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                StaggeredReveal(entered, 0) {
                    Column {
                        Text("Ещё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Все инструменты SecureMesh — разложены по назначению", color = SecureMeshColors.TextSecondary)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            item {
                StaggeredReveal(entered, 55) {
                    Column {
                        Text("Быстрый доступ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            QuickActionCard(
                                title = "Карта",
                                subtitle = if (canMapPoints) "Узлы и GPS-точки" else "Карта готова к GPS",
                                icon = Icons.Rounded.Map,
                                accent = SecureMeshColors.Cyan,
                                modifier = Modifier.weight(1f),
                            ) { open("map") }
                            QuickActionCard(
                                title = "Поиск",
                                subtitle = "Узлы, сообщения, события",
                                icon = Icons.Rounded.Search,
                                accent = SecureMeshColors.Blue,
                                modifier = Modifier.weight(1f),
                            ) { open("search") }
                        }
                    }
                }
            }

            if (networkItems.isNotEmpty()) {
                item {
                    StaggeredReveal(entered, 105) {
                        MenuSectionHeader("Сеть", "Как устроена mesh-сеть и куда идут пакеты", SecureMeshColors.Cyan)
                    }
                }
                items(networkItems, key = { it.route }) { destination ->
                    DestinationRow(destination) { open(destination.route) }
                }
            }

            if (toolItems.isNotEmpty()) {
                item {
                    StaggeredReveal(entered, 155) {
                        MenuSectionHeader("Инструменты", "Проверка, наблюдение и техническая работа", SecureMeshColors.Violet)
                    }
                }
                items(toolItems, key = { it.route }) { destination ->
                    DestinationRow(destination) { open(destination.route) }
                }
            }

            item {
                StaggeredReveal(entered, 205) {
                    MenuSectionHeader("Приложение", "Локальные параметры и поведение SecureMesh", SecureMeshColors.TextSecondary)
                }
            }
            items(systemItems, key = { it.route }) { destination ->
                DestinationRow(destination) { open(destination.route) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PressScaleSurface(
        onClick = onClick,
        modifier = modifier.height(132.dp),
        color = SecureMeshColors.SurfaceHigh,
        border = BorderStroke(1.dp, accent.copy(alpha = .28f)),
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = .14f)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MenuSectionHeader(title: String, subtitle: String, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(7.dp), shape = CircleShape, color = accent) {}
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DestinationRow(destination: MoreDestination, onClick: () -> Unit) {
    PressScaleSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .94f),
        border = BorderStroke(1.dp, destination.accent.copy(alpha = .16f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Surface(shape = CircleShape, color = destination.accent.copy(alpha = .13f)) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = destination.accent,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(destination.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(destination.description, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = destination.accent.copy(alpha = .78f), style = MaterialTheme.typography.headlineSmall)
        }
    }
}
