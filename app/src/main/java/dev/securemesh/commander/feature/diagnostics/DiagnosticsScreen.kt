package dev.securemesh.commander.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.BuildConfig
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.allowed) {
        EmptyState("Диагностика недоступна", "Узел должен поддерживать диагностику, а защищённая сессия — разрешать её просмотр.")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Диагностика", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Техническое состояние приложения, телефона и mesh-сети", color = SecureMeshColors.Muted)
        }
        item {
            TechnicalCard("Приложение") {
                DiagnosticRow("Версия", BuildConfig.VERSION_NAME)
                DiagnosticRow("Сборка", BuildConfig.VERSION_CODE.toString())
                DiagnosticRow("Транспорт", state.mode.ruLabel())
                DiagnosticRow("Демо-профиль", state.profile?.ruLabel() ?: "Не активен")
            }
        }
        item {
            TechnicalCard("Телефон") {
                DiagnosticRow("Bluetooth", phoneStateLabel(state.phoneBluetooth))
                DiagnosticRow("Разрешение BLE", permissionStateLabel(state.blePermission))
                Text("Если транспорт не дал актуальное наблюдение, приложение показывает «Нет данных», а не придумывает состояние.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            TechnicalCard("Локальный узел SecureMesh") {
                DiagnosticRow("Соединение", connectionLabel(state.connection))
                DiagnosticRow("ID", state.session?.localNodeIdentity?.nodeId ?: "Не определён")
                DiagnosticRow("Доступ", state.session?.authenticationState.ruLabel())
                DiagnosticRow("Роль", state.session?.localNodeIdentity?.role.ruLabel())
            }
        }
        item {
            TechnicalCard("Mesh-сеть") {
                DiagnosticRow("Видимых узлов", state.nodes.toString())
                DiagnosticRow("Направленных связей", state.links.toString())
                DiagnosticRow("Маршрутов", state.routes.toString())
                DiagnosticRow("Сообщений", state.messages.toString())
                DiagnosticRow("Последнее событие", state.events.firstOrNull()?.let { ageLabel(it.timestampEpochMs) } ?: "Нет данных")
            }
        }

        if (state.settings.developerMode) {
            item {
                TechnicalCard("Для разработчика") {
                    Text("Сценарии демо", fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        item { AssistChip({ viewModel.scenario("NORMAL") }, { Text("Норма") }) }
                        item { AssistChip({ viewModel.scenario("WEAK LINK") }, { Text("Слабая связь") }) }
                        item { AssistChip({ viewModel.scenario("RELAY LOST") }, { Text("Потеря relay") }) }
                        item { AssistChip({ viewModel.scenario("GPS LOST") }, { Text("Потеря GPS") }) }
                        item { AssistChip({ viewModel.scenario("MESSAGE RETRY") }, { Text("Повтор сообщения") }) }
                        item { AssistChip({ viewModel.scenario("SOS") }, { Text("SOS") }) }
                    }
                    HorizontalDivider(color = SecureMeshColors.Divider)
                    Text("Последние сырые события", fontWeight = FontWeight.SemiBold)
                    if (state.events.isEmpty()) {
                        Text("Системные события недоступны", color = SecureMeshColors.Muted)
                    } else {
                        state.events.take(10).forEach { event ->
                            Text(
                                "${clockLabel(event.timestampEpochMs)} · ${event.category.ruLabel()} · ${localizedTechnicalText(event.title)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SecureMeshColors.TextSecondary,
                            )
                        }
                    }
                    OutlinedButton(onClick = viewModel::clearHistory, modifier = Modifier.fillMaxWidth()) {
                        Text("Очистить локальную историю")
                    }
                }
            }
        } else {
            item {
                Text(
                    "Сырые BLE/debug-функции скрыты. Их можно включить в «Настройки → Для разработчика».",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SecureMeshColors.Muted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun phoneStateLabel(value: String): String = when (value.uppercase()) {
    "ENABLED" -> "Включён"
    "DISABLED" -> "Выключен"
    "UNAVAILABLE" -> "Недоступен"
    else -> "Нет данных"
}

private fun permissionStateLabel(value: String): String = when (value.uppercase()) {
    "GRANTED" -> "Разрешено"
    "REQUIRED" -> "Нужно разрешение"
    "DENIED" -> "Запрещено"
    else -> "Нет данных"
}

private fun connectionLabel(state: MeshConnectionState): String = when (state) {
    is MeshConnectionState.Connected -> if (state.secureSession == SecureSessionState.ESTABLISHED) "Защищённая сессия" else "Только BLE"
    is MeshConnectionState.Connecting -> "Подключение"
    is MeshConnectionState.Scanning, is MeshConnectionState.DeviceFound -> "Поиск устройств"
    is MeshConnectionState.Reconnecting -> "Переподключение"
    is MeshConnectionState.Error -> "Ошибка"
    is MeshConnectionState.Disconnected -> "Отключено"
    MeshConnectionState.Idle -> "Не подключено"
    else -> "Подготовка соединения"
}
