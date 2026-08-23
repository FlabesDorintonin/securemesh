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
        EmptyState(
            "Проверка состояния недоступна",
            "Подключите свой узел SecureMesh и подтвердите доступ.",
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Проверка устройства", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Показывает, готово ли приложение и подключённый узел к работе.", color = SecureMeshColors.Muted)
        }

        item {
            TechnicalCard("Приложение") {
                DiagnosticRow("Версия", BuildConfig.VERSION_NAME)
                DiagnosticRow("Состояние", if (state.connection is MeshConnectionState.Connected) "Готово" else connectionLabel(state.connection))
            }
        }

        item {
            TechnicalCard("Телефон") {
                DiagnosticRow("Bluetooth", phoneStateLabel(state.phoneBluetooth))
                DiagnosticRow("Доступ к устройствам", permissionStateLabel(state.blePermission))
            }
        }

        item {
            val ble = state.ble
            TechnicalCard("Связь с узлом") {
                DiagnosticRow("Подключение", connectionLabel(state.connection))
                DiagnosticRow("Доступ", if (state.session != null) "Подтверждён" else "Не подтверждён")
                DiagnosticRow(
                    "Устройство",
                    state.session?.localNodeIdentity?.displayName?.let(::deviceDisplayName) ?: "Не определено",
                )
                if (state.mode == TransportMode.BLE) {
                    DiagnosticRow("Версия устройства", ble?.firmwareVersion ?: "Нет данных")
                    DiagnosticRow("Защита", secureSessionLabel(ble?.secureSessionState))
                }
            }
        }

        item {
            TechnicalCard("Сеть") {
                DiagnosticRow("Узлов доступно", state.nodes.toString())
                DiagnosticRow("Связей", state.links.toString())
                DiagnosticRow("Путей", state.routes.toString())
                DiagnosticRow(
                    "Последнее изменение",
                    state.events.firstOrNull()?.let { ageLabel(it.timestampEpochMs) } ?: "Нет данных",
                )
            }
        }

        state.ble?.operationalHealth?.let { health ->
            item {
                TechnicalCard("Готовность сети") {
                    DiagnosticRow("Общая оценка", "${health.score}% · ${operationalLevelLabel(health.level)}")
                    DiagnosticRow("Радиосвязь", scoreLabel(health.radioScore))
                    DiagnosticRow("Сеть", scoreLabel(health.meshScore))
                    DiagnosticRow("Пути", scoreLabel(health.routingScore))
                    DiagnosticRow("Запасных путей", health.backupRouteCount.toString())
                }
            }
        }

        state.ble?.selfCheck?.let { check ->
            item {
                TechnicalCard("Проверка узла") {
                    DiagnosticRow("Радиомодуль", readinessLabel(check.radioReady))
                    DiagnosticRow("Защита", readinessLabel(check.protectionReady))
                    DiagnosticRow("Связь с телефоном", readinessLabel(check.phoneLinkReady))
                    DiagnosticRow("GPS", gpsCheckLabel(check.gpsState))
                    DiagnosticRow("Экран", readinessLabel(check.displayReady))
                }
            }
        }

        state.ble?.radar?.let { radar ->
            item {
                TechnicalCard("Радар устройств") {
                    DiagnosticRow("Сканирование", if (radar.scanning) "Работает" else if (radar.configured) "Ожидание" else "Недоступно")
                    DiagnosticRow("Устройств рядом", radar.devices.size.toString())
                    radar.devices.maxByOrNull { it.signalDbm }?.let { strongest ->
                        DiagnosticRow("Самый заметный сигнал", strongest.advertisedName ?: "Неизвестное устройство")
                        DiagnosticRow("Изменение сигнала", signalTrendLabel(strongest.signalTrendDb))
                    }
                }
            }
        }

        if (state.settings.developerMode) {
            item {
                Text(
                    "Инженерный режим",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SecureMeshColors.Warning,
                )
                Text(
                    "Данные ниже нужны только для разработки и поиска неисправностей.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.mode == TransportMode.BLE) {
                item {
                    val ble = state.ble
                    TechnicalCard("Инженерные данные соединения") {
                        DiagnosticRow("Node ID", ble?.nodeId ?: "Нет данных")
                        DiagnosticRow("BLE address", ble?.bleAddress ?: "Нет данных")
                        DiagnosticRow("GATT state", ble?.gattState ?: "Нет данных")
                        DiagnosticRow("Bonded", ble?.bonded?.let { if (it) "Да" else "Нет" } ?: "Нет данных")
                        DiagnosticRow("Application protocol", ble?.protocolVersion?.toString() ?: "Нет данных")
                        DiagnosticRow("Firmware", ble?.firmwareVersion ?: "Нет данных")
                        DiagnosticRow("MTU", ble?.mtu?.toString() ?: "Нет данных")
                        DiagnosticRow("RESPONSE subscription", if (ble?.responseSubscribed == true) "Да" else "Нет")
                        DiagnosticRow("EVENT subscription", if (ble?.eventSubscribed == true) "Да" else "Нет")
                        DiagnosticRow("Secure session", ble?.secureSessionState.ruLabel())
                        DiagnosticRow("Последний requestId", ble?.lastCommandRequestId?.toString() ?: "Нет данных")
                        DiagnosticRow("Последний RESPONSE", ble?.lastResponse ?: "Нет данных")
                        DiagnosticRow("Ошибки reassembly", ble?.reassemblyErrors?.toString() ?: "0")
                        DiagnosticRow("Malformed packets", ble?.malformedPacketCount?.toString() ?: "0")
                    }
                }
            }

            item {
                TechnicalCard("Для разработчика") {
                    DiagnosticRow("Сборка", BuildConfig.VERSION_CODE.toString())
                    DiagnosticRow("Транспорт", state.mode.ruLabel())
                    DiagnosticRow("Демо-профиль", state.profile?.ruLabel() ?: "Не активен")
                    if (state.mode == TransportMode.MOCK) {
                        Text("Сценарии демо", fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            item { AssistChip({ viewModel.scenario("NORMAL") }, { Text("Норма") }) }
                            item { AssistChip({ viewModel.scenario("WEAK LINK") }, { Text("Слабая связь") }) }
                            item { AssistChip({ viewModel.scenario("RELAY LOST") }, { Text("Потеря ретранслятора") }) }
                            item { AssistChip({ viewModel.scenario("GPS LOST") }, { Text("Потеря GPS") }) }
                            item { AssistChip({ viewModel.scenario("MESSAGE RETRY") }, { Text("Повтор сообщения") }) }
                            item { AssistChip({ viewModel.scenario("SOS") }, { Text("SOS") }) }
                        }
                        HorizontalDivider(color = SecureMeshColors.Divider)
                    }
                    Text("Последние события", fontWeight = FontWeight.SemiBold)
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

private fun secureSessionLabel(value: SecureSessionState?): String = when (value) {
    SecureSessionState.ESTABLISHED -> "Готова"
    SecureSessionState.AUTHENTICATING -> "Проверяется"
    SecureSessionState.NOT_CONFIGURED -> "Настраивается"
    SecureSessionState.NOT_AUTHENTICATED, null -> "Не подтверждена"
}

private fun operationalLevelLabel(level: OperationalLevel): String = when (level) {
    OperationalLevel.EXCELLENT -> "Отлично"
    OperationalLevel.GOOD -> "Хорошо"
    OperationalLevel.DEGRADED -> "Нестабильно"
    OperationalLevel.CRITICAL -> "Критично"
}

private fun scoreLabel(score: Int): String = when {
    score >= 86 -> "Отлично"
    score >= 68 -> "Хорошо"
    score >= 42 -> "Нестабильно"
    else -> "Плохо"
}

private fun readinessLabel(ready: Boolean): String = if (ready) "Исправно" else "Есть проблема"

private fun gpsCheckLabel(state: Int): String = when (state) {
    2 -> "Готов"
    1 -> "Ищет спутники"
    else -> "Недоступен"
}

private fun signalTrendLabel(trendDb: Int): String = when {
    trendDb >= 4 -> "Усиливается"
    trendDb <= -4 -> "Ослабевает"
    else -> "Стабильно"
}

private fun connectionLabel(state: MeshConnectionState): String = when (state) {
    is MeshConnectionState.Connected -> if (state.secureSession == SecureSessionState.ESTABLISHED) "Готово" else "Настройка соединения"
    is MeshConnectionState.Connecting -> "Подключение"
    is MeshConnectionState.PairingRequired -> "Подтвердите телефон"
    is MeshConnectionState.Authenticating -> "Проверка доступа"
    is MeshConnectionState.DiscoveringServices,
    is MeshConnectionState.IdentifyingSecureMesh,
    is MeshConnectionState.SyncingSession -> "Настройка соединения"
    is MeshConnectionState.Scanning, is MeshConnectionState.DeviceFound -> "Поиск устройств"
    is MeshConnectionState.Reconnecting -> "Переподключение"
    is MeshConnectionState.Error -> "Ошибка"
    is MeshConnectionState.Disconnected -> "Отключено"
    MeshConnectionState.Idle -> "Не подключено"
    else -> "Подготовка"
}
