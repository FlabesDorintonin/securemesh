package dev.securemesh.commander.feature.deviceui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun DeviceControlScreen(viewModel: DeviceControlViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var requestedInitialState by remember { mutableStateOf(false) }

    LaunchedEffect(state.allowed, state.device) {
        if (state.allowed && state.device == null && !requestedInitialState) {
            requestedInitialState = true
            viewModel.refresh()
        }
    }

    if (!state.allowed) {
        MeshBackdrop(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Управление устройством недоступно",
                    "Нужна защищённая SecureMesh-сессия с прошивкой, которая объявляет возможность UI OS.",
                )
            }
        }
        return
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Устройство", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Экран и меню физического узла SecureMesh", color = SecureMeshColors.TextSecondary)
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Обновить", tint = SecureMeshColors.CyanHot)
                    }
                }
            }

            state.error?.let { message ->
                item {
                    Surface(
                        color = SecureMeshColors.Warning.copy(alpha = .10f),
                        border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .30f)),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Команда не выполнена", fontWeight = FontWeight.Bold)
                            Text(message, color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::clearError) { Text("Закрыть") }
                        }
                    }
                }
            }

            item {
                TechnicalCard("Защищённая сессия") {
                    DeviceValueRow("Node ID", state.session?.localNodeIdentity?.nodeId ?: "—")
                    DeviceValueRow("Прошивка", state.session?.firmwareVersion ?: "—")
                    DeviceValueRow("BLE Protocol", state.session?.protocolVersion?.let { "v$it" } ?: "—")
                    DeviceValueRow("Доступ", "Аутентифицирован")
                }
            }

            val device = state.device
            if (device == null) {
                item {
                    TechnicalCard("Состояние OLED") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Получаю GET_UI_STATE от узла…",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("Повторить") }
                    }
                }
            } else {
                item {
                    DeviceStateCard(device)
                }

                if (device.plannedFeature) {
                    item {
                        Surface(
                            color = SecureMeshColors.Violet.copy(alpha = .09f),
                            border = BorderStroke(1.dp, SecureMeshColors.Violet.copy(alpha = .28f)),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Раздел ещё не активен", fontWeight = FontWeight.Bold)
                                Text(
                                    "Прошивка сама пометила «${device.feature.label}» как запланированную функцию. Приложение не подменяет её фиктивными действиями.",
                                    color = SecureMeshColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item {
                    DeviceRemote(
                        busy = state.busy,
                        action = viewModel::action,
                    )
                }

                item {
                    TechnicalCard("Живые данные UI OS") {
                        DeviceValueRow("Входящие", device.inboxCount.toString())
                        DeviceValueRow("Непрочитанные", device.unreadCount.toString())
                        DeviceValueRow("Соседи", device.neighborCount.toString())
                        DeviceValueRow("Маршруты", device.routeCount.toString())
                        DeviceValueRow("Field Test", if (device.fieldTestRunning) "Выполняется" else "Остановлен")
                        DeviceValueRow("BLE state", device.bleState.toString())
                    }
                }
            }

            item {
                Text(
                    "Пульт меняет только UI-состояние OLED через команды GET_UI_STATE / UI_ACTION прошивки 0.6.3. Он не создаёт радио-пакеты сам по себе.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceStateCard(device: DeviceUiState) {
    TechnicalCard("Экран узла") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(device.feature.takeUnless { it == DeviceUiFeature.NONE }?.label ?: device.menu.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("${device.scene.label} · ${device.menu.label}", color = SecureMeshColors.TextSecondary)
            }
            Surface(
                shape = CircleShape,
                color = if (device.oledReady && device.bleProtocolReady) SecureMeshColors.Healthy.copy(alpha = .14f) else SecureMeshColors.Warning.copy(alpha = .14f),
            ) {
                Text(
                    if (device.oledReady && device.bleProtocolReady) "SYNC" else "WAIT",
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = if (device.oledReady && device.bleProtocolReady) SecureMeshColors.Healthy else SecureMeshColors.Warning,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        HorizontalDivider(color = SecureMeshColors.Divider.copy(alpha = .6f))
        DeviceValueRow("UI model", "v${device.modelVersion}")
        DeviceValueRow("Позиция меню", "${device.menuIndex + 1}")
        DeviceValueRow("Глубина", device.navigationDepth.toString())
        AnimatedVisibility(device.toastVisible) {
            Text("На OLED сейчас отображается уведомление", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeviceRemote(busy: Boolean, action: (DeviceUiAction) -> Unit) {
    TechnicalCard("Пульт OLED") {
        Text("Управляй тем же меню, которое видно на физическом экране узла.", color = SecureMeshColors.TextSecondary)
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RemoteButton("↑", "Вверх", busy) { action(DeviceUiAction.UP) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteButton("‹", "Назад", busy) { action(DeviceUiAction.BACK) }
            RemoteButton("OK", "Выбрать", busy, emphasized = true) { action(DeviceUiAction.SELECT) }
            RemoteButton("⌂", "Домой", busy) { action(DeviceUiAction.HOME) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RemoteButton("↓", "Вниз", busy) { action(DeviceUiAction.DOWN) }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RemoteButton(
    symbol: String,
    description: String,
    busy: Boolean,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    if (emphasized) {
        Button(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.size(width = 88.dp, height = 60.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(symbol, fontWeight = FontWeight.ExtraBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.size(width = 78.dp, height = 56.dp),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .28f)),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(symbol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DeviceValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SecureMeshColors.Muted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
