package dev.securemesh.commander.feature.discovery

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    onBack: () -> Unit,
    onBleConnected: (secureProtocolReady: Boolean) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionRequestInFlight = false
        viewModel.onPermissionResult(result)
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.scan()
    }

    LaunchedEffect(Unit) { viewModel.scan() }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    LaunchedEffect(state.connection, state.session) {
        val connected = state.connection as? MeshConnectionState.Connected ?: return@LaunchedEffect
        val ready = connected.protocolConfigured &&
            connected.secureSession == SecureSessionState.ESTABLISHED &&
            state.session?.authenticationState == AuthenticationState.AUTHENTICATED
        if (ready) delay(420)
        onBleConnected(ready)
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Подключение", fontWeight = FontWeight.Bold, color = SecureMeshColors.Text) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConnectionJourney(state.connection)
                AnimatedContent(
                    targetState = state.connection,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "discovery-state",
                ) { connection ->
                    when (connection) {
                        is MeshConnectionState.PermissionRequired -> PermissionRequestCard(
                            denied = state.permissionDenied,
                            inFlight = permissionRequestInFlight,
                            onRequest = {
                                permissionRequestInFlight = true
                                permissionLauncher.launch(connection.permissions.toTypedArray())
                            },
                            onSettings = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                                context.startActivity(intent)
                            },
                        )

                        MeshConnectionState.BluetoothDisabled -> EnvironmentAction(
                            title = "Bluetooth выключен",
                            detail = "Включи Bluetooth — после возврата SecureMesh продолжит поиск автоматически.",
                            actionText = "Включить Bluetooth",
                        ) { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }

                        MeshConnectionState.BluetoothUnavailable -> EmptyState(
                            "Bluetooth недоступен",
                            "Телефон сообщает, что BLE-адаптер недоступен.",
                        )

                        is MeshConnectionState.PairingRequired -> SystemPairingHint(connection)
                        is MeshConnectionState.Authenticating,
                        is MeshConnectionState.DiscoveringServices,
                        is MeshConnectionState.IdentifyingSecureMesh,
                        is MeshConnectionState.SyncingSession -> AuthenticationHint(connection)

                        is MeshConnectionState.Connected -> SecureConnectedCard(connection)
                        else -> DeviceDiscoveryContent(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestCard(
    denied: Boolean,
    inFlight: Boolean,
    onRequest: () -> Unit,
    onSettings: () -> Unit,
) {
    TechnicalCard(if (denied) "Нужен доступ к Bluetooth" else "Разрешение Bluetooth") {
        Text(
            if (denied) {
                "Без доступа к устройствам поблизости Android не отдаёт приложению BLE-сканирование и подключение. Данные геолокации SecureMesh не запрашивает."
            } else {
                "SecureMesh использует системное разрешение Android «Устройства поблизости» только для поиска и подключения к локальному узлу."
            },
            color = SecureMeshColors.TextSecondary,
        )
        VibrantPrimaryButton(
            text = if (inFlight) "Ждём Android…" else if (denied) "Разрешить ещё раз" else "Разрешить Bluetooth",
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth(),
            enabled = !inFlight,
            icon = Icons.Rounded.BluetoothSearching,
        )
        if (denied) {
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть настройки приложения")
            }
        }
        Text(
            "Разрешение проверяется до обращения к Bluetooth-адаптеру — это защищает от сбоев на строгих OEM-прошивках Android.",
            color = SecureMeshColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EnvironmentAction(title: String, detail: String, actionText: String, action: () -> Unit) {
    TechnicalCard(title) {
        Text(detail, color = SecureMeshColors.TextSecondary)
        VibrantPrimaryButton(actionText, action, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConnectionJourney(connection: MeshConnectionState) {
    val stage = when (connection) {
        is MeshConnectionState.PermissionRequired -> 0
        MeshConnectionState.BluetoothDisabled,
        MeshConnectionState.BluetoothUnavailable,
        MeshConnectionState.Idle,
        is MeshConnectionState.Scanning,
        is MeshConnectionState.DeviceFound -> 1
        is MeshConnectionState.Connecting,
        is MeshConnectionState.DiscoveringServices -> 2
        is MeshConnectionState.PairingRequired -> 3
        is MeshConnectionState.Authenticating,
        is MeshConnectionState.IdentifyingSecureMesh,
        is MeshConnectionState.SyncingSession -> 4
        is MeshConnectionState.Connected -> 5
        else -> 1
    }
    val steps = listOf("Доступ", "Поиск", "BLE", "Pairing", "Защита")

    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .82f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .14f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                steps.forEachIndexed { index, label ->
                    val complete = stage > index
                    val active = stage == index
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier
                                .size(if (active) 12.dp else 9.dp)
                                .background(
                                    when {
                                        complete -> SecureMeshColors.Healthy
                                        active -> SecureMeshColors.CyanHot
                                        else -> SecureMeshColors.Divider
                                    },
                                    CircleShape,
                                )
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active || complete) SecureMeshColors.TextSecondary else SecureMeshColors.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemPairingHint(state: MeshConnectionState.PairingRequired) {
    TechnicalCard("Подтверди устройство") {
        Text(
            "Сверь 6-значный код на OLED узла с системным окном Android и подтверди сопряжение.",
            color = SecureMeshColors.TextSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = SecureMeshColors.CyanHot)
            Text("Код обрабатывает Android — приложение его не хранит и не отправляет через COMMAND.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Metric("Устройство", deviceDisplayName(state.device.advertisedName ?: state.device.address))
        Metric("Окно подтверждения", "до ${clockLabel(state.expiresAtEpochMs)}")
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan)
    }
}

@Composable
private fun AuthenticationHint(connection: MeshConnectionState) {
    val label = when (connection) {
        is MeshConnectionState.DiscoveringServices -> "Проверяем GATT service"
        is MeshConnectionState.IdentifyingSecureMesh -> "Проверяем SecureMesh identity"
        is MeshConnectionState.SyncingSession -> "Синхронизируем права сессии"
        else -> "Проверяем защищённый BLE-канал"
    }
    TechnicalCard("Защищённое подключение") {
        Text(label, fontWeight = FontWeight.SemiBold, color = SecureMeshColors.Text)
        Text(
            "Сессия откроется только после подтверждения протокола, bonding и аутентифицированного INFO/nodeId.",
            color = SecureMeshColors.TextSecondary,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan)
    }
}

@Composable
private fun SecureConnectedCard(connection: MeshConnectionState.Connected) {
    TechnicalCard("SecureMesh готов") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SecureMeshColors.Healthy, modifier = Modifier.size(28.dp))
            Column {
                Text("Защищённая сессия установлена", fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
                Text(deviceDisplayName(connection.device.advertisedName ?: connection.device.address), color = SecureMeshColors.TextSecondary)
            }
        }
        LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.Healthy)
    }
}

@Composable
private fun DeviceDiscoveryContent(state: DiscoveryUiState, viewModel: DiscoveryViewModel) {
    val scanning = state.connection is MeshConnectionState.Scanning || state.connection is MeshConnectionState.DeviceFound

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.filter.query,
            onValueChange = viewModel::setQuery,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text("Поиск устройства") },
            placeholder = { Text(if (state.showingUnknownBle) "Имя или BLE-адрес" else "SecureMesh-узел") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (state.showingUnknownBle) {
                FilterChip(
                    selected = state.filter.secureMeshOnly,
                    onClick = { viewModel.setSecureMeshOnly(!state.filter.secureMeshOnly) },
                    label = { Text("Только SecureMesh") },
                )
            }
            FilterChip(
                selected = state.filter.sort == DeviceSort.RSSI,
                onClick = { viewModel.setSort(DeviceSort.RSSI) },
                label = { Text("Сильнее сигнал") },
            )
        }
        if (!state.showingUnknownBle) {
            Text(
                "Показываются только устройства с подтверждённым SecureMesh Service UUID. Имя BLE не считается идентичностью.",
                color = SecureMeshColors.Muted,
                style = MaterialTheme.typography.bodySmall,
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
            OutlinedIconButton(onClick = viewModel::refresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
            }
        }
        AnimatedVisibility(visible = scanning) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan)
                Text("Ищем ближайшие BLE-устройства…", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (scanning) "Идёт поиск" else "Устройства не найдены",
                    if (scanning) "Оставь узел включённым рядом с телефоном." else "Запусти поиск ещё раз и проверь, что узел включён.",
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.devices, key = { it.address }) { device ->
                    DeviceCard(device) { viewModel.connect(device) }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onConnect: () -> Unit) {
    val name = deviceDisplayName(device.advertisedName)
    val secureCandidate = device.classification != DeviceClassification.UNKNOWN_BLE
    PressScaleSurface(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh,
        border = BorderStroke(
            1.dp,
            if (secureCandidate) SecureMeshColors.Cyan.copy(alpha = .30f) else SecureMeshColors.Divider,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeshAvatar(
                name,
                size = 50.dp,
                accent = if (secureCandidate) SecureMeshColors.Cyan else SecureMeshColors.Muted,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = SecureMeshColors.Text)
                    Text("${device.rssi} dBm", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelLarge)
                }
                Text(signalLabel(device.rssi), color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        if (secureCandidate) "SecureMesh" else "BLE",
                        if (secureCandidate) SecureMeshColors.Healthy else SecureMeshColors.Muted,
                    )
                    Text(device.bondStatus.ruLabel(), color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(device.address, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
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
        Column(
            Modifier.fillMaxWidth().widthIn(max = 620.dp).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusChip("BLE подключён", SecureMeshColors.Warning)
            Text("Узел не подтвердил SecureMesh", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
            Text(
                "GATT-соединение установлено, но защищённый SecureMesh Protocol v0.2 не прошёл проверку service/characteristics, версии или INFO handshake.",
                color = SecureMeshColors.TextSecondary,
            )
            TechnicalCard("Соединение") {
                Metric("Устройство", deviceDisplayName(connection.device.advertisedName ?: connection.device.address))
                Metric("Защищённая сессия", connection.secureSession.ruLabel())
                Metric("Протокол", if (connection.protocolConfigured) "Поддерживается" else "Не подтверждён")
            }
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Отключиться") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад к поиску") }
        }
    }
}
