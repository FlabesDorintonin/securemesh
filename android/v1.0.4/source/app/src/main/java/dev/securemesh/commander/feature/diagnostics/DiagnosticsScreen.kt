package dev.securemesh.commander.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
        EmptyState("Диагностика недоступна", "Узел должен поддерживать BLE/сетевую диагностику, а защищённая сессия — разрешать её просмотр.")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Диагностика", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Техническое состояние приложения, BLE protocol и mesh-сети", color = SecureMeshColors.Muted)
        }
        item {
            TechnicalCard("Приложение") {
                DiagnosticRow("Версия", BuildConfig.VERSION_NAME)
                DiagnosticRow("Сборка", BuildConfig.VERSION_CODE.toString())
                DiagnosticRow("Транспорт", state.mode.ruLabel())
            }
        }
        item {
            TechnicalCard("Телефон") {
                DiagnosticRow("Bluetooth", phoneStateLabel(state.phoneBluetooth))
                DiagnosticRow("Разрешение BLE", permissionStateLabel(state.blePermission))
                Text("Показываются только значения, подтверждённые текущим соединением; неизвестные данные остаются «Нет данных».", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.mode == TransportMode.BLE) {
            item {
                val ble = state.ble
                TechnicalCard("SecureMesh BLE v0.2") {
                    DiagnosticRow("Node ID", ble?.nodeId ?: "Нет данных")
                    DiagnosticRow("BLE address", ble?.bleAddress ?: "Нет данных")
                    DiagnosticRow("GATT state", ble?.gattState ?: "Нет данных")
                    DiagnosticRow("Bonded", ble?.bonded?.let { if (it) "Да" else "Нет" } ?: "Нет данных")
                    DiagnosticRow("Protocol version", ble?.protocolVersion?.toString() ?: "Нет данных")
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
        state.ble?.operationalHealth?.let { health ->
            item {
                TechnicalCard("Готовность узла v1.0.4") {
                    DiagnosticRow("Общая оценка", "${health.score}% · ${operationalLevelLabel(health.level)}")
                    DiagnosticRow("Радиосвязь", "${health.radioScore}%")
                    DiagnosticRow("Mesh", "${health.meshScore}%")
                    DiagnosticRow("Маршрутизация", "${health.routingScore}%")
                    DiagnosticRow("Память", "${health.memoryScore}%")
                    DiagnosticRow("Очередь", "${health.queueUsed}/${health.queueCapacity}")
                    DiagnosticRow("G2", health.backupRouteCount.toString())
                }
            }
        }
        state.ble?.selfCheck?.let { check ->
            item {
                TechnicalCard("Самодиагностика узла") {
                    DiagnosticRow("Радио", readinessLabel(check.radioReady))
                    DiagnosticRow("Криптография", readinessLabel(check.protectionReady))
                    DiagnosticRow("BLE", readinessLabel(check.phoneLinkReady))
                    DiagnosticRow("GPS", gpsCheckLabel(check.gpsState))
                    DiagnosticRow("OLED", readinessLabel(check.displayReady))
                    DiagnosticRow("Свободная память", "${check.freeHeapBytes} B")
                }
            }
        }
        state.ble?.radar?.let { radar ->
            item {
                TechnicalCard("BLE Radar узла") {
                    DiagnosticRow("Состояние", if (radar.scanning) "Сканирование" else if (radar.configured) "Готов" else "Недоступен")
                    DiagnosticRow("Устройств рядом", radar.devices.size.toString())
                    DiagnosticRow("Обнаружений", radar.totalDetections.toString())
                    radar.devices.maxByOrNull { it.signalDbm }?.let { strongest ->
                        DiagnosticRow("Сильнейший сигнал", strongest.advertisedName ?: "BLE ${strongest.addressHash.toString(16).uppercase()}")
                        DiagnosticRow("RSSI", "${strongest.signalDbm} dBm")
                    }
                }
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


    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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

private fun operationalLevelLabel(level: OperationalLevel): String = when (level) {
    OperationalLevel.EXCELLENT -> "Отлично"
    OperationalLevel.GOOD -> "Хорошо"
    OperationalLevel.DEGRADED -> "Нестабильно"
    OperationalLevel.CRITICAL -> "Критично"
}

private fun readinessLabel(value: Boolean): String = if (value) "OK" else "Ошибка"

private fun gpsCheckLabel(state: Int): String = when (state) {
    2 -> "Свежий fix"
    1 -> "Нет свежего fix"
    0 -> "UART недоступен"
    else -> "Нет данных"
}

private fun connectionLabel(state: MeshConnectionState): String = when (state) {
    is MeshConnectionState.Connected -> if (state.secureSession == SecureSessionState.ESTABLISHED) "PROTOCOL_READY" else "Только GATT"
    is MeshConnectionState.Connecting -> "Подключение"
    is MeshConnectionState.PairingRequired -> "Системное pairing"
    is MeshConnectionState.Authenticating -> "Аутентификация"
    is MeshConnectionState.DiscoveringServices -> "Service discovery"
    is MeshConnectionState.IdentifyingSecureMesh -> "INFO handshake"
    is MeshConnectionState.SyncingSession -> "Session sync"
    is MeshConnectionState.Scanning, is MeshConnectionState.DeviceFound -> "Поиск устройств"
    is MeshConnectionState.Reconnecting -> "Переподключение"
    is MeshConnectionState.Error -> "Ошибка"
    is MeshConnectionState.Disconnected -> "Отключено"
    MeshConnectionState.Idle -> "Не подключено"
    else -> "Подготовка соединения"
}
