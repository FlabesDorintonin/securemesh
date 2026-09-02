package dev.securemesh.commander.feature.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var showTechnical by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { entered = true }

    if (!state.allowed) {
        EmptyState("Проверка исправности недоступна", "Подключённый узел не разрешает просмотр своего состояния.")
        return
    }

    val check = state.ble?.selfCheck
    val radioDegradedButControllable = check?.let { !it.radioReady && it.phoneLinkReady && it.displayReady } == true

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StaggeredReveal(entered, 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Проверка исправности", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Показываем простыми словами, что сейчас работает, а что требует внимания.", color = SecureMeshColors.TextSecondary)
                }
            }
        }

        if (radioDegradedButControllable) {
            item {
                StaggeredReveal(entered, 45) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = SecureMeshColors.Warning.copy(alpha = .12f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Радиосвязь требует внимания", fontWeight = FontWeight.Bold, color = SecureMeshColors.Warning)
                            Text(
                                "Управление через телефон и экран узла остаются доступны. Можно продолжать проверку и отдельно разбираться с радиосвязью.",
                                color = SecureMeshColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 80) {
                TechnicalCard("Основное состояние") {
                    DiagnosticRow("Связь с телефоном", connectionLabel(state.connection))
                    DiagnosticRow("Радиосвязь", check?.radioReady?.let(::readinessLabel) ?: "Нет данных")
                    DiagnosticRow("Экран узла", check?.displayReady?.let(::readinessLabel) ?: "Нет данных")
                    DiagnosticRow("Защита", check?.protectionReady?.let(::readinessLabel) ?: state.ble?.secureSessionState.ruLabel())
                    DiagnosticRow("Спутниковая навигация", check?.let { gpsCheckLabel(it.gpsState) } ?: "Нет данных")
                }
            }
        }

        state.ble?.operationalHealth?.let { health ->
            item {
                StaggeredReveal(entered, 120) {
                    TechnicalCard("Общая готовность узла") {
                        DiagnosticRow("Оценка", "${health.score}% · ${operationalLevelLabel(health.level)}")
                        DiagnosticRow("Радиосвязь", "${health.radioScore}%")
                        DiagnosticRow("Сеть", "${health.meshScore}%")
                        DiagnosticRow("Поиск пути", "${health.routingScore}%")
                        DiagnosticRow("Память", "${health.memoryScore}%")
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 160) {
                TechnicalCard("Сеть SecureMesh") {
                    DiagnosticRow("Видимых узлов", state.nodes.toString())
                    DiagnosticRow("Связей между узлами", state.links.toString())
                    DiagnosticRow("Известных путей", state.routes.toString())
                    DiagnosticRow("Сообщений", state.messages.toString())
                    DiagnosticRow("Последнее событие", state.events.firstOrNull()?.let { ageLabel(it.timestampEpochMs) } ?: "Нет данных")
                }
            }
        }

        item {
            TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showTechnical) "Скрыть технические сведения" else "Показать технические сведения")
            }
        }

        item {
            AnimatedVisibility(visible = showTechnical) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TechnicalCard("Приложение") {
                        DiagnosticRow("Версия", BuildConfig.VERSION_NAME)
                        DiagnosticRow("Номер сборки", BuildConfig.VERSION_CODE.toString())
                        DiagnosticRow("Способ связи", state.mode.ruLabel())
                    }

                    TechnicalCard("Телефон") {
                        DiagnosticRow("Связь на телефоне", phoneStateLabel(state.phoneBluetooth))
                        DiagnosticRow("Разрешение на подключение", permissionStateLabel(state.blePermission))
                    }

                    state.ble?.let { ble ->
                        TechnicalCard("Подключённый узел") {
                            DiagnosticRow("Номер узла", ble.nodeId ?: "Нет данных")
                            DiagnosticRow("Адрес подключения", ble.bleAddress ?: "Нет данных")
                            DiagnosticRow("Сопряжение", ble.bonded?.let { if (it) "Выполнено" else "Не выполнено" } ?: "Нет данных")
                            DiagnosticRow("Версия обмена", ble.protocolVersion?.toString() ?: "Нет данных")
                            DiagnosticRow("Версия прошивки", ble.firmwareVersion ?: "Нет данных")
                            DiagnosticRow("Размер блока обмена", ble.mtu.toString())
                            DiagnosticRow("Канал ответов", if (ble.responseSubscribed) "Активен" else "Не активен")
                            DiagnosticRow("Канал событий", if (ble.eventSubscribed) "Активен" else "Не активен")
                            DiagnosticRow("Защищённое соединение", ble.secureSessionState.ruLabel())
                            DiagnosticRow("Номер последней команды", ble.lastCommandRequestId?.toString() ?: "Нет данных")
                            DiagnosticRow("Ошибки сборки данных", ble.reassemblyErrors.toString())
                            DiagnosticRow("Повреждённые данные", ble.malformedPacketCount.toString())
                        }
                    }

                    check?.let {
                        TechnicalCard("Самопроверка узла") {
                            DiagnosticRow("Радиосвязь", readinessLabel(it.radioReady))
                            DiagnosticRow("Защита", readinessLabel(it.protectionReady))
                            DiagnosticRow("Связь с телефоном", readinessLabel(it.phoneLinkReady))
                            DiagnosticRow("Навигация", gpsCheckLabel(it.gpsState))
                            DiagnosticRow("Экран", readinessLabel(it.displayReady))
                            DiagnosticRow("Свободная память", "${it.freeHeapBytes} Б")
                        }
                    }

                    state.ble?.operationalHealth?.let { health ->
                        TechnicalCard("Нагрузка и запасные пути") {
                            DiagnosticRow("Очередь", "${health.queueUsed} из ${health.queueCapacity}")
                            DiagnosticRow("Запасных путей", health.backupRouteCount.toString())
                        }
                    }

                    state.ble?.radar?.let { radar ->
                        TechnicalCard("Устройства рядом") {
                            DiagnosticRow("Поиск", if (radar.scanning) "Выполняется" else if (radar.configured) "Готов" else "Недоступен")
                            DiagnosticRow("Устройств рядом", radar.devices.size.toString())
                            DiagnosticRow("Всего обнаружений", radar.totalDetections.toString())
                            radar.devices.maxByOrNull { it.signalDbm }?.let { strongest ->
                                DiagnosticRow("Самое заметное устройство", strongest.advertisedName ?: "Устройство ${strongest.addressHash.toString(16).uppercase()}")
                                DiagnosticRow("Сила сигнала", "${strongest.signalDbm} дБм")
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
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
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun phoneStateLabel(value: String): String = when (value.uppercase()) {
    "ENABLED" -> "Включена"
    "DISABLED" -> "Выключена"
    "UNAVAILABLE" -> "Недоступна"
    else -> "Нет данных"
}

private fun permissionStateLabel(value: String): String = when (value.uppercase()) {
    "GRANTED" -> "Разрешено"
    "REQUIRED" -> "Требуется разрешение"
    "DENIED" -> "Запрещено"
    else -> "Нет данных"
}

private fun operationalLevelLabel(level: OperationalLevel): String = when (level) {
    OperationalLevel.EXCELLENT -> "Отлично"
    OperationalLevel.GOOD -> "Хорошо"
    OperationalLevel.DEGRADED -> "Есть ограничения"
    OperationalLevel.CRITICAL -> "Требует внимания"
}

private fun readinessLabel(value: Boolean): String = if (value) "Готово" else "Не готово"

private fun gpsCheckLabel(state: Int): String = when (state) {
    2 -> "Координаты актуальны"
    1 -> "Нет свежих координат"
    0 -> "Модуль недоступен"
    else -> "Нет данных"
}

private fun connectionLabel(state: MeshConnectionState): String = when (state) {
    is MeshConnectionState.Connected -> if (state.secureSession == SecureSessionState.ESTABLISHED) "Готово к работе" else "Соединение установлено, проверка продолжается"
    is MeshConnectionState.Connecting -> "Подключение"
    is MeshConnectionState.PairingRequired -> "Нужно подтвердить сопряжение"
    is MeshConnectionState.Authenticating -> "Проверка доступа"
    is MeshConnectionState.DiscoveringServices -> "Проверка возможностей узла"
    is MeshConnectionState.IdentifyingSecureMesh -> "Проверка совместимости"
    is MeshConnectionState.SyncingSession -> "Синхронизация"
    is MeshConnectionState.Scanning, is MeshConnectionState.DeviceFound -> "Поиск устройств"
    is MeshConnectionState.Reconnecting -> "Повторное подключение"
    is MeshConnectionState.Error -> "Ошибка подключения"
    is MeshConnectionState.Disconnected -> "Отключено"
    MeshConnectionState.Idle -> "Не подключено"
    MeshConnectionState.BluetoothUnavailable -> "Связь на телефоне недоступна"
    MeshConnectionState.BluetoothDisabled -> "Связь на телефоне выключена"
    is MeshConnectionState.PermissionRequired -> "Требуется разрешение"
    MeshConnectionState.Disconnecting -> "Отключение"
}
