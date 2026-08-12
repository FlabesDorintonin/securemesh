package dev.securemesh.commander.feature.discovery

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.BuildConfig
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onBack: () -> Unit,
    onBleConnected: (secureProtocolReady: Boolean) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.scan() }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.scan() }

    LaunchedEffect(Unit) { viewModel.scan() }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }
    LaunchedEffect(state.connection, state.session) {
        val connected = state.connection as? MeshConnectionState.Connected ?: return@LaunchedEffect
        val ready = connected.protocolConfigured &&
            connected.secureSession == SecureSessionState.ESTABLISHED &&
            state.session?.authenticationState == AuthenticationState.AUTHENTICATED
        onBleConnected(ready)
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Подключение", fontWeight = FontWeight.Bold, color = SecureMeshColors.Text) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConnectionBanner(state.connection)
                when (val connection = state.connection) {
                    is MeshConnectionState.PermissionRequired -> EnvironmentAction(
                        title = "Разреши доступ к Bluetooth",
                        detail = "Android требует разрешение на поиск и подключение к ближайшим BLE-устройствам.",
                        actionText = "Разрешить",
                    ) { permissionLauncher.launch(connection.permissions.toTypedArray()) }

                    MeshConnectionState.BluetoothDisabled -> EnvironmentAction(
                        title = "Bluetooth выключен",
                        detail = "Включи Bluetooth, чтобы найти ближайшие узлы SecureMesh.",
                        actionText = "Включить Bluetooth",
                    ) { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }

                    MeshConnectionState.BluetoothUnavailable -> EmptyState("Bluetooth недоступен", "Телефон сообщает, что BLE-адаптер отсутствует.")
                    is MeshConnectionState.PairingRequired -> SystemPairingHint(connection)
                    is MeshConnectionState.Authenticating -> AuthenticationHint()
                    else -> DeviceDiscoveryContent(state, diagnostics, viewModel)
                }
            }
        }
    }
}

@Composable
private fun EnvironmentAction(title: String, detail: String, actionText: String, action: () -> Unit) {
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
        Text(detail, color = SecureMeshColors.Muted)
        VibrantPrimaryButton(actionText, action, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SystemPairingHint(state: MeshConnectionState.PairingRequired) {
    TechnicalCard("Системное сопряжение Android") {
        Text("Посмотри 6-значный код на OLED узла и введи его в системном окне Bluetooth Android.", color = SecureMeshColors.TextSecondary)
        Text("Код не передаётся через COMMAND characteristic и не хранится приложением.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        Metric("Устройство", deviceDisplayName(state.device.advertisedName ?: state.device.address))
        Metric("Окно pairing", "до ${clockLabel(state.expiresAtEpochMs)}")
    }
}

@Composable
private fun AuthenticationHint() {
    TechnicalCard("Проверка защищённого соединения") {
        Text("Сопряжение подтверждено. Ждём authenticated/encrypted BLE link, подписки RESPONSE/EVENT и INFO handshake.", color = SecureMeshColors.TextSecondary)
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan)
    }
}

@Composable
private fun DeviceDiscoveryContent(state: DiscoveryUiState, diagnostics: BleDiagnostics?, viewModel: DiscoveryViewModel) {
    val scanning = state.connection is MeshConnectionState.Scanning || state.connection is MeshConnectionState.DeviceFound
    val scanHealth = diagnostics?.lastResponse ?: "ожидаем первый ScanResult"

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.filter.query,
            onValueChange = viewModel::setQuery,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text("Поиск устройства") },
            placeholder = { Text("Имя или BLE-адрес") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(
                selected = state.filter.secureMeshOnly,
                onClick = { viewModel.setSecureMeshOnly(!state.filter.secureMeshOnly) },
                label = { Text("Только SecureMesh") },
            )
            FilterChip(
                selected = state.filter.sort == DeviceSort.RSSI,
                onClick = { viewModel.setSort(DeviceSort.RSSI) },
                label = { Text("Сильнее сигнал") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (scanning) viewModel.stopScan() else viewModel.scan() },
                colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Cyan, contentColor = Color(0xFF001E28)),
            ) {
                Icon(Icons.Rounded.BluetoothSearching, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(if (scanning) "Остановить" else "Искать", fontWeight = FontWeight.SemiBold)
            }
            OutlinedIconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Обновить") }
        }
        AnimatedVisibility(visible = scanning) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan) }

        Surface(
            color = SecureMeshColors.Surface.copy(alpha = .72f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .7f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Сканер: Android default · без фильтров",
                    color = SecureMeshColors.CyanHot,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Версия: ${BuildConfig.VERSION_NAME}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text("RAW: $scanHealth", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(
                    "В списке сейчас: ${state.devices.size}. Identity проверяется только после GATT + INFO.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (state.devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Устройства пока не найдены", "Смотри строку RAW выше: она показывает, получает ли Android ScanResult вообще.")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.devices, key = { it.address }) { device -> DeviceCard(device) { viewModel.connect(device) } }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onConnect: () -> Unit) {
    val name = deviceDisplayName(device.advertisedName)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onConnect,
        color = SecureMeshColors.SurfaceHigh,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, if (device.classification == DeviceClassification.UNKNOWN_BLE) SecureMeshColors.Divider else SecureMeshColors.Cyan.copy(alpha = .22f)),
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeshAvatar(name, size = 48.dp, accent = if (device.classification == DeviceClassification.UNKNOWN_BLE) SecureMeshColors.Muted else SecureMeshColors.Cyan)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = SecureMeshColors.Text)
                    Text("${device.rssi} dBm", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelLarge)
                }
                Text(signalLabel(device.rssi), color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("${device.classification.ruLabel()} · ${device.bondStatus.ruLabel()}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(device.address, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = SecureMeshColors.Cyan.copy(alpha = .65f))
        }
    }
}

@Composable
fun ProtocolUnavailableScreen(
    connection: MeshConnectionState.Connected,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    MeshBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 620.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusChip("BLE подключён", SecureMeshColors.Warning)
            Text("Несовместимый SecureMesh protocol", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
            Text(
                "Устройство подключилось по GATT, но не подтвердило поддерживаемый SecureMesh BLE Protocol v0.1: service/characteristics могут быть неправильными или firmware использует другую protocolVersion.",
                color = SecureMeshColors.TextSecondary,
            )
            TechnicalCard("Соединение") {
                Metric("Устройство", deviceDisplayName(connection.device.advertisedName ?: connection.device.address))
                Metric("Защищённая сессия", connection.secureSession.ruLabel())
                Metric("Протокол", if (connection.protocolConfigured) "Поддерживается" else "Не поддерживается")
            }
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Отключиться") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад к поиску") }
        }
    }
}
