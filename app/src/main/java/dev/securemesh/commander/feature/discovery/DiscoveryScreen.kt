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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.scan() }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.scan() }

    LaunchedEffect(Unit) { viewModel.scan() }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }
    LaunchedEffect(state.connection, state.session) {
        val connected = state.connection as? MeshConnectionState.Connected ?: return@LaunchedEffect
        val ready = connected.protocolConfigured && connected.secureSession == SecureSessionState.ESTABLISHED && state.session?.authenticationState == AuthenticationState.AUTHENTICATED
        onBleConnected(ready)
    }

    val step = when (state.connection) {
        is MeshConnectionState.PairingRequired,
        is MeshConnectionState.Authenticating,
        is MeshConnectionState.DiscoveringServices,
        is MeshConnectionState.IdentifyingSecureMesh,
        is MeshConnectionState.SyncingSession -> 1
        is MeshConnectionState.Connected -> 2
        else -> 0
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Подключение", fontWeight = FontWeight.ExtraBold)
                            Text("Найди ближайший SecureMesh", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionStepStrip(step)
                ConnectionBanner(state.connection)
                when (val connection = state.connection) {
                    is MeshConnectionState.PermissionRequired -> EnvironmentAction(
                        title = "Разреши Bluetooth",
                        detail = "Без разрешения Android не даст приложению искать ближайшие BLE-узлы.",
                        actionText = "Разрешить",
                    ) { permissionLauncher.launch(connection.permissions.toTypedArray()) }
                    MeshConnectionState.BluetoothDisabled -> EnvironmentAction(
                        title = "Bluetooth выключен",
                        detail = "Включи Bluetooth — интернет и Wi-Fi для SecureMesh не нужны.",
                        actionText = "Включить Bluetooth",
                    ) { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    MeshConnectionState.BluetoothUnavailable -> EmptyState("Bluetooth недоступен", "Телефон сообщает, что BLE-адаптер отсутствует.")
                    is MeshConnectionState.PairingRequired -> PairingHint(connection)
                    is MeshConnectionState.Authenticating -> AuthenticationHint()
                    else -> DeviceDiscoveryContent(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun EnvironmentAction(title: String, detail: String, actionText: String, action: () -> Unit) {
    OsHeroCard("Нужно действие", title, detail, SecureMeshColors.Warning) {
        VibrantPrimaryButton(actionText, action, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PairingHint(state: MeshConnectionState.PairingRequired) {
    OsHeroCard(
        eyebrow = "Шаг 2 из 3",
        title = "Введи код с OLED",
        subtitle = "На экране узла появился одноразовый 6-значный код. Введи его в системном окне Android Bluetooth.",
        accent = SecureMeshColors.Warning,
        status = "PAIRING",
    ) {
        Metric("Устройство", deviceDisplayName(state.device.advertisedName ?: state.device.address))
        Text("Код не передаётся внутри SecureMesh COMMAND и не хранится приложением.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AuthenticationHint() {
    OsHeroCard(
        eyebrow = "Почти готово",
        title = "Проверяем защищённую сессию",
        subtitle = "Pairing подтверждён. Сейчас приложение проверяет GATT, подписки RESPONSE/EVENT и protocol v0.1.",
        accent = SecureMeshColors.Cyan,
    ) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan) }
}

@Composable
private fun DeviceDiscoveryContent(state: DiscoveryUiState, viewModel: DiscoveryViewModel) {
    val scanning = state.connection is MeshConnectionState.Scanning || state.connection is MeshConnectionState.DeviceFound
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.filter.query,
            onValueChange = viewModel::setQuery,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text("Поиск") },
            placeholder = { Text("Имя или BLE-адрес") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.filter.secureMeshOnly, onClick = { viewModel.setSecureMeshOnly(!state.filter.secureMeshOnly) }, label = { Text("Только SecureMesh") })
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Обновить") }
            Button(
                onClick = { if (scanning) viewModel.stopScan() else viewModel.scan() },
                colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Cyan, contentColor = Color(0xFF001E28)),
            ) {
                Icon(Icons.Rounded.BluetoothSearching, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (scanning) "Стоп" else "Искать", fontWeight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(visible = scanning) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan) }
        Text("Настоящий SecureMesh определяется по Service UUID, а не только по имени устройства.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (state.devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Пока никого не видно", if (scanning) "Поиск идёт. Держи узел включённым рядом с телефоном." else "Нажми «Искать», чтобы запустить BLE-сканирование.")
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
    val secureMeshCandidate = device.classification != DeviceClassification.UNKNOWN_BLE
    val accent = if (secureMeshCandidate) SecureMeshColors.Cyan else SecureMeshColors.Muted
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, if (secureMeshCandidate) accent.copy(alpha = .25f) else SecureMeshColors.Divider),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MeshAvatar(name, size = 48.dp, accent = accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            SignalBars(device.rssi, activeColor = accent)
                            Text("${device.rssi} dBm", color = accent, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(if (secureMeshCandidate) "${device.classification.ruLabel()} · ${device.bondStatus.ruLabel()}" else "Обычное BLE-устройство", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(device.address, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = secureMeshCandidate,
                colors = ButtonDefaults.buttonColors(containerColor = if (secureMeshCandidate) SecureMeshColors.Cyan else SecureMeshColors.SurfaceBright, contentColor = Color(0xFF001E28)),
            ) { Text(if (secureMeshCandidate) "Подключиться" else "Не SecureMesh", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun ProtocolUnavailableScreen(connection: MeshConnectionState.Connected, onDisconnect: () -> Unit, onBack: () -> Unit) {
    MeshBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 620.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OsHeroCard(
                eyebrow = "Несовместимо",
                title = "BLE есть, SecureMesh protocol — нет",
                subtitle = "Устройство подключилось по GATT, но не подтвердило SecureMesh BLE Protocol v0.1.",
                accent = SecureMeshColors.Warning,
            ) {
                Metric("Устройство", deviceDisplayName(connection.device.advertisedName ?: connection.device.address))
                Metric("Защищённая сессия", connection.secureSession.ruLabel())
                Metric("Протокол", if (connection.protocolConfigured) "Поддерживается" else "Не поддерживается")
            }
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Отключиться") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад к поиску") }
        }
    }
}
