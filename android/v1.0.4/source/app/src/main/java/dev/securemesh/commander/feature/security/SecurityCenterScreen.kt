package dev.securemesh.commander.feature.security

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*

@Composable
fun SecurityCenterScreen(viewModel: SecurityCenterViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val protected = state.settings.secureScreen

    MeshBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    color = SecureMeshColors.Healthy.copy(alpha = .08f),
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, SecureMeshColors.Healthy.copy(alpha = .22f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(shape = CircleShape, color = SecureMeshColors.Healthy.copy(alpha = .13f)) {
                            Icon(Icons.Rounded.Security, null, tint = SecureMeshColors.Healthy, modifier = Modifier.padding(12.dp).size(28.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Центр безопасности", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            Text("Проверяем не обещания, а реально включённые механизмы", color = SecureMeshColors.TextSecondary)
                        }
                        StatusChip(if (protected) "PROTECTED" else "LIMITED", if (protected) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                    }
                }
            }

            item {
                SecurityCard(Icons.Rounded.VisibilityOff, "Защита экрана", if (protected) "Включена" else "Выключена", protected) {
                    Text(
                        if (state.overlayProtectionSupported) {
                            "FLAG_SECURE блокирует снимки экрана и небезопасный вывод. На Android 12+ дополнительно включена защита от сторонних application overlays."
                        } else {
                            "FLAG_SECURE блокирует снимки экрана и небезопасный вывод. Защита от application overlays доступна начиная с Android 12."
                        },
                        color = SecureMeshColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = protected, onCheckedChange = viewModel::setSecureScreen)
                }
            }

            item {
                SecurityCard(Icons.Rounded.EnhancedEncryption, "Локальный криптовольт", "AES-256-GCM", true) {
                    SecurityValue("Ключ", "Android Keystore · non-exportable")
                    SecurityValue("Контакты/заметки", "Зашифрованы")
                    SecurityValue("Текст сообщений", "Зашифрован")
                    SecurityValue("GPS history", "Зашифрована")
                    Text("Для каждого значения используется отдельный GCM IV; AAD привязывает ciphertext к конкретному nodeId/messageId/position record и не позволяет незаметно переставить его между записями.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("При обновлении со старых сборок plaintext GPS-кэш один раз удаляется. Старые сообщения перезаписываются в vault при запуске.", color = SecureMeshColors.Warning, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                SecurityCard(Icons.Rounded.PhoneAndroid, "Изоляция приложения", "Локально", true) {
                    Text("У приложения нет INTERNET permission. Android backup и device-transfer backup отключены; FLAG_SECURE и защита overlays остаются включаемыми на уровне всего окна.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("Экспортированный вручную CSV/JSON уже находится вне криптовольта — экспорт нужно считать обычным чувствительным файлом.", color = SecureMeshColors.Warning, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                SecurityCard(Icons.Rounded.BluetoothConnected, "BLE-аутентификация", if (state.authenticated) "Сессия подтверждена" else "Нет активной защищённой сессии", state.authenticated) {
                    SecurityValue("Node ID", state.session?.localNodeIdentity?.nodeId ?: "—")
                    SecurityValue("System bond", state.bonded?.let { if (it) "Да" else "Нет" } ?: "—")
                    SecurityValue("BLE protocol", state.ble?.protocolVersion?.toString() ?: "—")
                    Text("BLE address используется только как транспортная подсказка. Доверие назначается после pairing + INFO/nodeId + PROTOCOL_READY.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                SecurityCard(Icons.Rounded.Key, "Доверенное устройство", if (state.settings.rememberTrustedNode) "Запоминание включено" else "Не запоминается", state.settings.rememberTrustedNode) {
                    SettingToggle("Запоминать доверенный nodeId", state.settings.rememberTrustedNode, viewModel::setRememberTrustedNode)
                    SettingToggle("Автоподключение", state.settings.autoReconnect, viewModel::setAutoReconnect, enabled = state.settings.rememberTrustedNode)
                    if (!state.settings.rememberTrustedNode) {
                        Text("Сохранённая trusted-запись удаляется сразу; старый BLE address больше не используется для reconnect.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                SecurityCard(Icons.Rounded.Storage, "Хранение истории", "${state.settings.retentionDays} дней", true) {
                    SecurityValue("События", if (state.settings.storeEvents) "Хранятся" else "Не сохраняются")
                    SecurityValue("GPS history", if (state.settings.positionHistory) "Хранится" else "Не сохраняется")
                    Text("Чаты сохраняются локально по SecureMesh identity. Message key включает origin + messageId, чтобы одинаковые firmware ID разных отправителей не перетирали историю.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SecurityCard(icon: ImageVector, title: String, status: String, ok: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .90f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, (if (ok) SecureMeshColors.Healthy else SecureMeshColors.Warning).copy(alpha = .18f)),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = if (ok) SecureMeshColors.Healthy else SecureMeshColors.Warning, modifier = Modifier.size(22.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(status, color = if (ok) SecureMeshColors.Healthy else SecureMeshColors.Warning, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = SecureMeshColors.Divider.copy(alpha = .55f))
            content()
        }
    }
}

@Composable
private fun SecurityValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SecureMeshColors.Muted)
        Text(value, color = SecureMeshColors.TextSecondary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = SecureMeshColors.TextSecondary)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
