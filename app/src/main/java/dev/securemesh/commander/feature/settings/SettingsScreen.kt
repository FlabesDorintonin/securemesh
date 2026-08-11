package dev.securemesh.commander.feature.settings

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
import dev.securemesh.commander.core.ui.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Поведение приложения, Bluetooth и локальное хранение", color = SecureMeshColors.Muted)
        }

        item {
            SettingsGroup("Основное") {
                SettingValue("Тема", if (settings.theme.equals("dark", true)) "Тёмная" else settings.theme)
                SettingValue("Единицы", if (settings.units.equals("metric", true)) "Метрические" else settings.units)
                SettingSwitch("Не выключать экран во время теста", settings.keepScreenAwakeDuringTest) { value ->
                    viewModel.update { it.copy(keepScreenAwakeDuringTest = value) }
                }
            }
        }

        item {
            SettingsGroup("Bluetooth") {
                SettingSwitch("Автоматическое переподключение", settings.autoReconnect) { value ->
                    viewModel.update { it.copy(autoReconnect = value) }
                }
                SettingStepper(
                    "Длительность поиска",
                    "${settings.scanDurationSec} сек",
                    onMinus = { viewModel.update { it.copy(scanDurationSec = (it.scanDurationSec - 1).coerceAtLeast(5)) } },
                    onPlus = { viewModel.update { it.copy(scanDurationSec = (it.scanDurationSec + 1).coerceAtMost(30)) } },
                )
                SettingSwitch("Показывать неизвестные BLE-устройства", settings.showUnknownBle) { value ->
                    viewModel.update { it.copy(showUnknownBle = value) }
                }
                SettingSwitch("Запоминать доверенный узел SecureMesh", settings.rememberTrustedNode) { value ->
                    viewModel.update { it.copy(rememberTrustedNode = value) }
                }
            }
        }

        item {
            SettingsGroup("Карта и история") {
                SettingValue("Офлайн-карта", "Локальный провайдер")
                SettingSwitch("Хранить историю позиций", settings.positionHistory) { value ->
                    viewModel.update { it.copy(positionHistory = value) }
                }
            }
        }

        item {
            SettingsGroup("Журнал") {
                SettingSwitch("Сохранять события локально", settings.storeEvents) { value ->
                    viewModel.update { it.copy(storeEvents = value) }
                }
                SettingStepper(
                    "Хранить историю",
                    "${settings.retentionDays} дн",
                    onMinus = { viewModel.update { it.copy(retentionDays = (it.retentionDays - 1).coerceAtLeast(1)) } },
                    onPlus = { viewModel.update { it.copy(retentionDays = (it.retentionDays + 1).coerceAtMost(365)) } },
                )
                SettingValue("Экспорт", "JSON / CSV на устройство")
            }
        }

        item {
            SettingsGroup("Для разработчика") {
                SettingSwitch("Режим разработчика", settings.developerMode) { value -> viewModel.update { it.copy(developerMode = value) } }
                SettingSwitch("Демо-транспорт", settings.mockMode) { value -> viewModel.update { it.copy(mockMode = value) } }
                SettingSwitch("Сырой BLE", settings.rawBle) { value -> viewModel.update { it.copy(rawBle = value) } }
                SettingSwitch("Подробные логи", settings.verboseLogs) { value -> viewModel.update { it.copy(verboseLogs = value) } }
                SettingSwitch("Симулировать ошибки", settings.simulateFailures) { value -> viewModel.update { it.copy(simulateFailures = value) } }
            }
        }

        item {
            Text(
                "Все данные приложения сохраняются локально. Сетевой backend для работы SecureMesh не требуется.",
                color = SecureMeshColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    TechnicalCard(title) {
        content()
    }
}

@Composable
private fun SettingValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = SecureMeshColors.Muted)
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = set)
    }
}

@Composable
private fun SettingStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedIconButton(onClick = onMinus, modifier = Modifier.size(40.dp)) { Text("−") }
            Text(value, color = SecureMeshColors.TextSecondary)
            OutlinedIconButton(onClick = onPlus, modifier = Modifier.size(40.dp)) { Text("+") }
        }
    }
}
