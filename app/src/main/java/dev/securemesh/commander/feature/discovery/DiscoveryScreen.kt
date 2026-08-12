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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
                    title = {
                        Column {
                            Text("Подключение", fontWeight = FontWeight.ExtraBold, color = SecureMeshColors.Text)
                            Text("SecureMesh BLE", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelSmall)
                        }
                    },
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
                ConnectionRequirementsStrip(state.connection)
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
                            detail = "Включи Bluetooth. После возврата SecureMesh сам продолжит поиск узла.",
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
private fun ConnectionRequirementsStrip(connection: MeshConnectionState) {
    val bluetoothReady = connection !is MeshConnectionState.BluetoothDisabled && connection !is MeshConnectionState.BluetoothUnavailable
    val permissionReady = connection !is MeshConnectionState.PermissionRequired
    val secureReady = connection is MeshConnectionState.Connected && connection.protocolConfigured && connection.secureSession == SecureSessionState.ESTABLISHED

    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Что нужно для связи", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Android + узел должны пройти все пункты", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                StatusChip(if (secureReady) "ГОТОВО" else "ПРОВЕРКА", if (secureReady) SecureMeshColors.Healthy else SecureMeshColors.CyanHot)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RequirementPill("Bluetooth", bluetoothReady, Modifier.weight(1f))
                RequirementPill("Доступ", permissionReady, Modifier.weight(1f))
                RequirementPill("FW 0.6.3", secureReady, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RequirementPill(label: String, ready: Boolean, modifier: Modifier = Modifier) {
    val accent = if (ready) SecureMeshColors.Healthy else SecureMeshColors.Warning
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = .08f),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = .18f)),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Text(label, color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
    Surface(
        color = SecureMeshColors.Warning.copy(alpha = .09f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .30f)),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            StatusChip("ANDROID REQUIREMENT", SecureMeshColors.Warning)
            Text(if (denied) "Разреши Bluetooth-доступ" else "Нужен доступ к устройствам поблизости", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                if (denied) {
                    "Без этого Android блокирует BLE-сканирование и подключение. SecureMesh не запрашивает геолокацию."
                } else {
                    "Это системное разрешение Android для поиска и подключения к локальному SecureMesh-узлу."
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
        }
    }
}

@Composable
private fun EnvironmentAction(title: String, detail: String, actionText: String, action: () -> Unit) {
    Surface(
        color = SecureMeshColors.Warning.copy(alpha = .09f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .30f)),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusChip("ANDROID REQUIREMENT", SecureMeshColors.Warning)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(detail, color = SecureMeshColors.TextSecondary)
            VibrantPrimaryButton(actionText, action, Modifier.fillMaxWidth(), icon = Icons.Rounded.BluetoothSearching)
        }
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
    val steps = listOf("Доступ", "Поиск", "BLE", "Код", "Защита")
    val activeText = when (stage) {
        0 -> "Разрешение Android"
        1 -> "Ищем SecureMesh"
        2 -> "Соединяем GATT"
        3 -> "Введи CODE с OLED"
        4 -> "Проверяем SecureMesh"
        else -> "Защищённая сессия готова"
    }

    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .82f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .14f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(activeText, color = if (stage >= 5) SecureMeshColors.Healthy else SecureMeshColors.CyanHot, fontWeight = FontWeight.Bold)
                Text("${stage.coerceAtMost(5)}/5", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
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
    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .94f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.CyanHot.copy(alpha = .34f)),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusChip("PAIRING", SecureMeshColors.CyanHot)
                Text("до ${clockLabel(state.expiresAtEpochMs)}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text("Посмотри на OLED", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color.Black, Color(0xFF00151B))))
                    .border(1.dp, SecureMeshColors.Cyan.copy(alpha = .30f), RoundedCornerShape(20.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("CODE", color = SecureMeshColors.CyanHot, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold)
                    Text("6 DIGITS", color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("введи код с физического OLED в системное окно Android", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = SecureMeshColors.Healthy)
                Text("Код не передаётся приложению: его обрабатывает системный Bluetooth Android.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Metric("Устройство", deviceDisplayName(state.device.advertisedName ?: state.device.address))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.CyanHot, trackColor = SecureMeshColors.Divider)
        }
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
    Surface(
        color = SecureMeshColors.Cyan.copy(alpha = .075f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .22f)),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusChip("SECURITY CHECK", SecureMeshColors.CyanHot)
            Text(label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = SecureMeshColors.Text)
            Text(
                "Открываю интерфейс только после bonding, проверки service UUID, Protocol v0.1 и аутентифицированного INFO/nodeId.",
                color = SecureMeshColors.TextSecondary,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.CyanHot, trackColor = SecureMeshColors.Divider)
        }
    }
}

@Composable
private fun SecureConnectedCard(connection: MeshConnectionState.Connected) {
    Surface(
        color = SecureMeshColors.Healthy.copy(alpha = .08f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Healthy.copy(alpha = .30f)),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SecureMeshColors.Healthy, modifier = Modifier.size(32.dp))
                Column {
                    Text("SecureMesh готов", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = SecureMeshColors.Text)
                    Text(deviceDisplayName(connection.device.advertisedName ?: connection.device.address), color = SecureMeshColors.TextSecondary)
                }
            }
            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.Healthy, trackColor = SecureMeshColors.Divider)
        }
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
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    color = SecureMeshColors.Warning.copy(alpha = .09f),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .34f)),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        StatusChip("BLE ЕСТЬ · SECUREMESH НЕТ", SecureMeshColors.Warning)
                        Text("Проверь прошивку узла", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "Телефон физически подключился по BLE, но узел не подтвердил защищённый SecureMesh Protocol v0.1. Это не обычная ошибка Bluetooth.",
                            color = SecureMeshColors.TextSecondary,
                        )
                    }
                }

                TechnicalCard("Что должно совпасть") {
                    RequirementLine("Прошивка", "SecureMesh 0.6.3 alignment", true)
                    RequirementLine("BLE service", "SecureMesh UUID", connection.protocolConfigured)
                    RequirementLine("Protocol", "v0.1 + INFO handshake", connection.protocolConfigured)
                    RequirementLine("Secure session", connection.secureSession.ruLabel(), connection.secureSession == SecureSessionState.ESTABLISHED)
                }

                TechnicalCard("Подключённое устройство") {
                    Metric("Устройство", deviceDisplayName(connection.device.advertisedName ?: connection.device.address))
                    Metric("BLE", "Подключён")
                    Metric("Защищённая сессия", connection.secureSession.ruLabel())
                }

                VibrantPrimaryButton("Отключиться и повторить", onDisconnect, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад к поиску") }
            }
        }
    }
}

@Composable
private fun RequirementLine(label: String, value: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).background(if (ready) SecureMeshColors.Healthy else SecureMeshColors.Warning, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(label, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = SecureMeshColors.Text, fontWeight = FontWeight.SemiBold)
        }
        Text(if (ready) "OK" else "CHECK", color = if (ready) SecureMeshColors.Healthy else SecureMeshColors.Warning, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
