package dev.securemesh.commander.feature.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
)

@Composable
fun MoreScreen(session: SecureMeshSession?, open: (String) -> Unit) {
    val menuItems = buildList {
        if (UiAccessPolicy.canShowMap(session)) add(MoreDestination("Карта", "Позиции доступных узлов на локальной карте", "map", Icons.Rounded.Map))
        if (UiAccessPolicy.canShowTopology(session)) add(MoreDestination("Схема сети", "Узлы и направленные радиосвязи", "topology", Icons.Rounded.Hub))
        if (UiAccessPolicy.canShowRoutes(session)) add(MoreDestination("Маршруты", "Прямые и статические маршруты mesh-сети", "routes", Icons.Rounded.AltRoute))
        if (UiAccessPolicy.canRunFieldTest(session)) add(MoreDestination("Полевой тест", "Проверка связи, RSSI, SNR и повторов", "fieldtest", Icons.Rounded.Science))
        if (UiAccessPolicy.canShowSystemLog(session)) add(MoreDestination("События", "Журнал доступных событий системы", "events", Icons.Rounded.Notifications))
        if (UiAccessPolicy.canShowDiagnostics(session)) add(MoreDestination("Диагностика", "Состояние приложения, BLE и mesh-сети", "diagnostics", Icons.Rounded.BugReport))
        add(MoreDestination("Поиск", "Поиск по видимым узлам, сообщениям и событиям", "search", Icons.Rounded.Search))
        add(MoreDestination("Настройки", "Bluetooth, хранение данных и режим разработчика", "settings", Icons.Rounded.Settings))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Ещё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Инструменты SecureMesh для текущей сессии", color = SecureMeshColors.Muted)
            Spacer(Modifier.height(4.dp))
        }
        items(menuItems, key = { it.route }) { destination ->
            MenuRow(
                title = destination.title,
                subtitle = destination.description,
                icon = destination.icon,
                onClick = { open(destination.route) },
            )
        }
        item {
            Text(
                "Доступные разделы зависят от возможностей узла и разрешений защищённой сессии.",
                color = SecureMeshColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
}
