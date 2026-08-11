package dev.securemesh.commander.feature.discovery

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(viewModel: DiscoveryViewModel, onBack: () -> Unit, onBleConnected: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.scan() }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.scan() }
    LaunchedEffect(Unit) { viewModel.scan() }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }
    LaunchedEffect(state.connection) {
        if (state.connection is MeshConnectionState.Connected) onBleConnected()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BLE DISCOVERY") }, navigationIcon = { TextButton(onClick = onBack) { Text("BACK") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(state.connection)
            when (val connection = state.connection) {
                is MeshConnectionState.PermissionRequired -> EnvironmentAction("Bluetooth permission required", "Android requires explicit nearby-device permission for BLE scan/connect.") {
                    permissionLauncher.launch(connection.permissions.toTypedArray())
                }
                MeshConnectionState.BluetoothDisabled -> EnvironmentAction("Bluetooth is disabled", "Enable Bluetooth, then SecureMesh can run a bounded scan session.") {
                    bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                MeshConnectionState.BluetoothUnavailable -> EmptyState("BLE unavailable", "This device reports no Bluetooth adapter.")
                else -> DeviceDiscoveryContent(state, viewModel)
            }
        }
    }
}

@Composable
private fun EnvironmentAction(title: String, detail: String, action: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(detail, color = SecureMeshColors.Muted)
        Button(onClick = action) { Text("CONTINUE") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDiscoveryContent(state: DiscoveryUiState, viewModel: DiscoveryViewModel) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.filter.query,
            onValueChange = viewModel::setQuery,
            label = { Text("Search name or address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.filter.secureMeshOnly, { viewModel.setSecureMeshOnly(!state.filter.secureMeshOnly) }, { Text("SECUREMESH ONLY") })
            FilterChip(state.filter.sort == DeviceSort.RSSI, { viewModel.setSort(DeviceSort.RSSI) }, { Text("RSSI") })
            FilterChip(state.filter.sort == DeviceSort.NAME, { viewModel.setSort(DeviceSort.NAME) }, { Text("NAME") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val scanning = state.connection is MeshConnectionState.Scanning || state.connection is MeshConnectionState.DeviceFound
            Button(onClick = { if (scanning) viewModel.stopScan() else viewModel.scan() }) { Text(if (scanning) "STOP SCAN" else "START SCAN") }
            OutlinedButton(onClick = viewModel::refresh) { Text("REFRESH") }
        }
        Text("Scan sessions are time-limited; restart explicitly when needed.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (state.devices.isEmpty()) {
            EmptyState("No devices yet", "Nearby BLE advertisements will appear here. Unknown devices can be shown in development mode.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.devices, key = { it.address }) { device -> DeviceCard(device) { viewModel.connect(device) } }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onConnect: () -> Unit) {
    TechnicalCard(device.advertisedName ?: "UNNAMED BLE DEVICE", Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(device.address, style = MaterialTheme.typography.bodyMedium)
                Text("RSSI ${device.rssi} dBm · ${signalLabel(device.rssi)}", fontWeight = FontWeight.SemiBold)
                Text("Last seen ${ageLabel(device.lastSeenEpochMs)} · ${device.bondStatus}", color = SecureMeshColors.Muted)
            }
            StatusChip(
                when (device.classification) {
                    DeviceClassification.TRUSTED_SECUREMESH -> "TRUSTED"
                    DeviceClassification.KNOWN_SECUREMESH -> "KNOWN SECUREMESH"
                    DeviceClassification.SECUREMESH_CANDIDATE -> "CANDIDATE"
                    DeviceClassification.UNKNOWN_BLE -> "UNKNOWN BLE"
                },
                if (device.classification == DeviceClassification.UNKNOWN_BLE) SecureMeshColors.Muted else SecureMeshColors.Cyan,
            )
        }
        if (device.matchReasons.isNotEmpty()) Text(device.matchReasons.joinToString(" · "), color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("CONNECT") }
    }
}

@Composable
fun ProtocolUnavailableScreen(connection: MeshConnectionState.Connected, onDisconnect: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusChip("BLE CONNECTED", SecureMeshColors.Healthy)
            Text("SECUREMESH PROTOCOL NOT AVAILABLE / NOT CONFIGURED", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("The Android GATT link is real, but no SecureMesh Service UUID / characteristic contract has been approved in firmware yet. The app therefore does not invent commands or treat the link as an authenticated SecureMesh session.", color = SecureMeshColors.Muted)
            TechnicalCard("Connection") {
                Metric("Device", connection.device.advertisedName ?: connection.device.address)
                Metric("Secure session", connection.secureSession.name)
                Metric("Protocol configured", connection.protocolConfigured.toString())
            }
            Button(onClick = onDisconnect) { Text("DISCONNECT") }
            OutlinedButton(onClick = onBack) { Text("BACK TO DISCOVERY") }
        }
    }
}

@Composable
fun PairingCodeScreen(deviceName: String, expires: String, code: String, onCode: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("ENTER CODE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Device: $deviceName\nExpires: $expires", color = SecureMeshColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(6) { index ->
                Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.medium) {
                    Text(code.getOrNull(index)?.toString() ?: "_", Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        OutlinedTextField(value = code.take(6), onValueChange = { value -> onCode(value.filter(Char::isDigit).take(6)) }, label = { Text("6-digit code") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("CANCEL") }
            Button(onClick = onConfirm, enabled = code.length == 6) { Text("CONFIRM") }
        }
        Text("PairingController is intentionally a contract only until firmware security is defined.", color = SecureMeshColors.Warning)
    }
}
