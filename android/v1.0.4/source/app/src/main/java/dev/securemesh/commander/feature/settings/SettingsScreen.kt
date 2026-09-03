package dev.securemesh.commander.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Безопасность, Bluetooth и локальные данные", color = SecureMeshColors.Muted)
            }
        }

        item {
            ProductSettingsGroup("Безопасность", Icons.Rounded.Lock, SecureMeshColors.Cyan) {
                SettingSwitch(
                    label = "Защищать экран SecureMesh",
                    description = "Блокирует снимки экрана, небезопасный вывод и сторонние окна поверх чувствительного интерфейса.",
                    value = settings.secureScreen,
                ) { value -> viewModel.update { it.copy(secureScreen = value) } }

                HorizontalDivider(color = SecureMeshColors.Divider)

                SettingSwitch(
                    label = "Запоминать доверенный узел",
                    description = "BLE-адрес используется только как подсказка для поиска. Доверие каждый раз подтверждается защищённой сессией и SecureMesh NodeID.",
                    value = settings.rememberTrustedNode,
                ) { value -> viewModel.update { it.copy(rememberTrustedNode = value) } }

                HorizontalDivider(color = SecureMeshColors.Divider)
                SettingValue("Резервное копирование", "Отключено")
                SettingValue("Облачный сервер", "Не используется")

                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, SecureMeshColors.Critical.copy(alpha = .38f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecureMeshColors.Critical),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Очистить локальную историю")
                }
            }
        }

        item {
            ProductSettingsGroup("Bluetooth", Icons.Rounded.Bluetooth, SecureMeshColors.Blue) {
                SettingSwitch(
                    "Автоматическое переподключение",
                    "Повторно ищет последний доверенный узел, но не переносит доверие только по Bluetooth-адресу.",
                    settings.autoReconnect,
                    enabled = settings.rememberTrustedNode,
                ) { value -> viewModel.update { it.copy(autoReconnect = value) } }

                HorizontalDivider(color = SecureMeshColors.Divider)

                SettingStepper(
                    "Длительность поиска",
                    "${settings.scanDurationSec} сек",
                    onMinus = { viewModel.update { it.copy(scanDurationSec = (it.scanDurationSec - 1).coerceAtLeast(5)) } },
                    onPlus = { viewModel.update { it.copy(scanDurationSec = (it.scanDurationSec + 1).coerceAtMost(30)) } },
                )

                HorizontalDivider(color = SecureMeshColors.Divider)

                SettingSwitch(
                    "Диагностический поиск всех BLE",
                    "Доступно только в инженерном режиме. В обычном режиме показываются только устройства с сервисом SecureMesh.",
                    settings.showUnknownBle,
                    enabled = settings.developerMode,
                ) { value -> viewModel.update { it.copy(showUnknownBle = value) } }
            }
        }

        item {
            ProductSettingsGroup("Локальные данные", Icons.Rounded.Storage, SecureMeshColors.Violet) {
                SettingSwitch(
                    "История позиций",
                    "Сохранять подтверждённые координаты локально на телефоне.",
                    settings.positionHistory,
                ) { value -> viewModel.update { it.copy(positionHistory = value) } }

                HorizontalDivider(color = SecureMeshColors.Divider)

                SettingSwitch(
                    "Журнал событий",
                    "Сохранять события текущего подтверждённого SecureMesh NodeID локально.",
                    settings.storeEvents,
                ) { value -> viewModel.update { it.copy(storeEvents = value) } }

                HorizontalDivider(color = SecureMeshColors.Divider)

                SettingStepper(
                    "Срок хранения",
                    "${settings.retentionDays} дн",
                    onMinus = { viewModel.update { it.copy(retentionDays = (it.retentionDays - 1).coerceAtLeast(1)) } },
                    onPlus = { viewModel.update { it.copy(retentionDays = (it.retentionDays + 1).coerceAtMost(365)) } },
                )

                Text(
                    "При ручном экспорте файл покидает закрытое хранилище приложения — дальше его защищает уже выбранное место сохранения.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            ProductSettingsGroup("Интерфейс", null, SecureMeshColors.TextSecondary) {
                SettingValue("Тема", "Тёмная")
                SettingValue("Единицы", "Метрические")
                SettingSwitch(
                    "Не выключать экран во время теста",
                    "Удобно для полевого теста связи.",
                    settings.keepScreenAwakeDuringTest,
                ) { value -> viewModel.update { it.copy(keepScreenAwakeDuringTest = value) } }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить локальную историю?") },
            text = { Text("Будут удалены локальные сообщения, события, известные узлы, позиции и результаты полевых тестов. Доверенный NodeID останется.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Очистить", color = SecureMeshColors.Critical) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProductSettingsGroup(
    title: String,
    icon: ImageVector?,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .92f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, accent.copy(alpha = .18f)),
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun SettingValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = SecureMeshColors.TextSecondary)
        Text(value, color = SecureMeshColors.Text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingSwitch(label: String, description: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(description, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value, onCheckedChange = set, enabled = enabled)
    }
}

@Composable
private fun SettingStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedIconButton(onClick = onMinus, modifier = Modifier.size(40.dp)) { Text("−") }
            Text(value, color = SecureMeshColors.TextSecondary, fontWeight = FontWeight.Medium)
            OutlinedIconButton(onClick = onPlus, modifier = Modifier.size(40.dp)) { Text("+") }
        }
    }
}
