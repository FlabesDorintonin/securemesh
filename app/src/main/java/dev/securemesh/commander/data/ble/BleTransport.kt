package dev.securemesh.commander.data.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.securemesh.commander.data.transport.MeshTransport
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleTransport(
    private val context: Context,
    private val config: BleProtocolConfig = BleProtocolConfig.Development,
    private val codec: SecureMeshBleCodec = UnconfiguredSecureMeshBleCodec(),
    private val now: () -> Long = System::currentTimeMillis,
) : MeshTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val matcher = SecureMeshDeviceMatcher(config)
    private val scanResults = ConcurrentHashMap<String, DiscoveredDevice>()
    private var scanTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var disconnectTimeoutJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private var connectingDevice: DiscoveredDevice? = null

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Idle)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()
    private val _session = MutableStateFlow<SecureMeshSession?>(null)
    override val session: StateFlow<SecureMeshSession?> = _session.asStateFlow()
    private val _demoProfile = MutableStateFlow<DemoProfile?>(null)
    override val demoProfile: StateFlow<DemoProfile?> = _demoProfile.asStateFlow()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices = _discoveredDevices.asStateFlow()
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes = _nodes.asStateFlow()
    private val _topology = MutableStateFlow(MeshTopology(emptyList(), emptyList(), 0L))
    override val topology = _topology.asStateFlow()
    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    override val messages = _messages.asStateFlow()
    private val _routes = MutableStateFlow<List<MeshRoute>>(emptyList())
    override val routes = _routes.asStateFlow()
    private val _events = MutableStateFlow<List<MeshEvent>>(emptyList())
    override val events = _events.asStateFlow()
    private val _activeFieldTest = MutableStateFlow<FieldTestSession?>(null)
    override val activeFieldTest = _activeFieldTest.asStateFlow()
    private val _activeSos = MutableStateFlow<SosAlert?>(null)
    override val activeSos = _activeSos.asStateFlow()

    override suspend fun start() {
        _connectionState.value = environmentState()
    }

    override suspend fun stop() {
        stopScan()
        connectionTimeoutJob?.cancel()
        disconnectTimeoutJob?.cancel()
        closeGatt()
        connectingDevice = null
        _session.value = null
        _demoProfile.value = null
        _connectionState.value = environmentState()
    }

    override suspend fun startScan(durationMs: Long) {
        when (val environment = environmentState()) {
            MeshConnectionState.Idle -> Unit
            else -> {
                _connectionState.value = environment
                return
            }
        }
        val boundedDurationMs = durationMs.coerceIn(5_000L, 30_000L)
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.SCAN_FAILED, "BluetoothLeScanner unavailable", "BLE scanner is not available on this phone")
            )
            return
        }

        stopScanInternal(updateState = false)
        scanResults.clear()
        _discoveredDevices.value = emptyList()
        val start = now()
        _connectionState.value = MeshConnectionState.Scanning(start, start + boundedDurationMs)
        addEvent(EventCategory.RADIO, "BLE SCAN STARTED", "Session ${boundedDurationMs}ms")
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            scanTimeoutJob = scope.launch {
                delay(boundedDurationMs)
                stopScanInternal(updateState = true)
            }
        } catch (security: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        } catch (t: Throwable) {
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.SCAN_FAILED, t.message ?: t::class.java.simpleName, "BLE scan failed")
            )
        }
    }

    override suspend fun stopScan() = stopScanInternal(updateState = true)

    override suspend fun connect(device: DiscoveredDevice) {
        when (val environment = environmentState()) {
            MeshConnectionState.Idle -> Unit
            else -> {
                _connectionState.value = environment
                return
            }
        }
        stopScanInternal(updateState = false)
        closeGatt()
        connectingDevice = device
        _connectionState.value = MeshConnectionState.Connecting(device)
        addEvent(EventCategory.SYSTEM, "BLE CONNECTING", device.advertisedName ?: device.address)

        val remote = try {
            adapter?.getRemoteDevice(device.address)
        } catch (t: Throwable) {
            null
        }
        if (remote == null) {
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.CONNECTION_LOST, "Remote device unavailable", "Cannot open the selected BLE device")
            )
            return
        }

        try {
            disconnectTimeoutJob?.cancel()
            val newGatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (newGatt == null) {
                _connectionState.value = MeshConnectionState.Error(
                    MeshError(MeshErrorCode.CONNECTION_LOST, "connectGatt returned null", "Could not open a BLE GATT connection")
                )
                return
            }
            gatt = newGatt
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = scope.launch {
                delay(12_000)
                if (_connectionState.value is MeshConnectionState.Connecting || _connectionState.value is MeshConnectionState.DiscoveringServices) {
                    closeGatt()
                    _connectionState.value = MeshConnectionState.Error(
                        MeshError(MeshErrorCode.CONNECTION_TIMEOUT, "GATT connect timeout", "Connection timed out")
                    )
                }
            }
        } catch (security: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        } catch (t: Throwable) {
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.CONNECTION_LOST, t.message ?: "connectGatt failed", "Could not connect to this device")
            )
        }
    }

    override suspend fun disconnect() {
        connectionTimeoutJob?.cancel()
        disconnectTimeoutJob?.cancel()
        val currentGatt = gatt
        if (currentGatt == null) {
            connectingDevice = null
            _session.value = null
            _connectionState.value = MeshConnectionState.Disconnected("No active BLE link")
            return
        }
        _connectionState.value = MeshConnectionState.Disconnecting
        try {
            currentGatt.disconnect()
            disconnectTimeoutJob = scope.launch {
                delay(3_000)
                if (_connectionState.value is MeshConnectionState.Disconnecting) {
                    closeGatt()
                    connectingDevice = null
                    _session.value = null
                    _connectionState.value = MeshConnectionState.Disconnected("BLE disconnect timeout; local GATT closed")
                }
            }
        } catch (_: SecurityException) {
            closeGatt()
            connectingDevice = null
            _session.value = null
            _connectionState.value = MeshConnectionState.Disconnected("Permission revoked")
        }
    }

    override suspend fun sendMessage(destination: NodeId, payload: String): Result<MessageId> = protocolUnavailable()
    override suspend fun addStaticRoute(destination: NodeId, via: NodeId): Result<Unit> = protocolUnavailable()
    override suspend fun removeRoute(destination: NodeId): Result<Unit> = protocolUnavailable()
    override suspend fun startFieldTest(config: FieldTestConfig): Result<String> = protocolUnavailable()
    override suspend fun stopFieldTest() = Unit
    override suspend fun acknowledgeSos(id: String) = Unit

    private fun <T> protocolUnavailable(): Result<T> = Result.failure(
        IllegalStateException("BLE_CONNECTED but SecureMesh GATT command protocol is not configured")
    )

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.SCAN_FAILED, "Android scan error $errorCode", "BLE scan failed (code $errorCode)")
            )
            addEvent(EventCategory.RADIO, "BLE SCAN FAILED", "Android error $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name
            val services = record?.serviceUuids?.map { it.uuid }?.toSet().orEmpty()
            val manufacturer = buildMap<Int, ByteArray> {
                val array = record?.manufacturerSpecificData ?: return@buildMap
                for (i in 0 until array.size()) put(array.keyAt(i), array.valueAt(i))
            }
            val match = matcher.match(AdvertisementSnapshot(name, services, manufacturer))
            if (!config.showAllBleDevices && match.classification == DeviceClassification.UNKNOWN_BLE) return
            val address = result.device.address
            val bond = when (result.device.bondState) {
                BluetoothDevice.BOND_BONDED -> BondStatus.BONDED
                BluetoothDevice.BOND_BONDING -> BondStatus.BONDING
                BluetoothDevice.BOND_NONE -> BondStatus.NOT_BONDED
                else -> BondStatus.UNKNOWN
            }
            scanResults[address] = DiscoveredDevice(
                address = address,
                advertisedName = name,
                rssi = result.rssi,
                lastSeenEpochMs = now(),
                classification = match.classification,
                bondStatus = bond,
                protocolVersion = match.protocolVersion,
                deviceType = match.deviceType,
                matchReasons = match.reasons,
            )
            _discoveredDevices.value = scanResults.values.sortedByDescending { it.rssi }
            val end = when (val state = _connectionState.value) {
                is MeshConnectionState.Scanning -> state.endsAtEpochMs
                is MeshConnectionState.DeviceFound -> state.scanEndsAtEpochMs
                else -> now()
            }
            _connectionState.value = MeshConnectionState.DeviceFound(_discoveredDevices.value.size, end)
        } catch (_: SecurityException) {
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = connectingDevice ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionTimeoutJob?.cancel()
                closeGatt()
                _connectionState.value = MeshConnectionState.Error(
                    MeshError(MeshErrorCode.CONNECTION_LOST, "GATT status $status", "BLE connection was lost")
                )
                addEvent(EventCategory.SYSTEM, "BLE CONNECTION ERROR", "GATT status $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = MeshConnectionState.DiscoveringServices(device)
                    addEvent(EventCategory.SYSTEM, "BLE LINK CONNECTED", "Discovering GATT services")
                    try {
                        if (!gatt.discoverServices()) {
                            _connectionState.value = MeshConnectionState.Error(
                                MeshError(MeshErrorCode.PROTOCOL_MISMATCH, "discoverServices returned false", "Could not inspect device services")
                            )
                        }
                    } catch (_: SecurityException) {
                        _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    disconnectTimeoutJob?.cancel()
                    closeGatt()
                    _session.value = null
                    _connectionState.value = MeshConnectionState.Disconnected("BLE link disconnected")
                    addEvent(EventCategory.SYSTEM, "BLE DISCONNECTED", "GATT disconnected")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            connectionTimeoutJob?.cancel()
            val device = connectingDevice ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = MeshConnectionState.Error(
                    MeshError(MeshErrorCode.PROTOCOL_MISMATCH, "Service discovery status $status", "GATT service discovery failed")
                )
                return
            }
            val expected: UUID? = config.serviceUuid
            val serviceDetected = expected != null && gatt.services.any { it.uuid == expected }
            val characteristicsConfigured = config.commandCharacteristicUuid != null && config.eventCharacteristicUuid != null
            val protocolConfigured = serviceDetected && characteristicsConfigured && codec.configured
            _connectionState.value = MeshConnectionState.Connected(
                device = device,
                linkRssi = device.rssi,
                secureSession = if (protocolConfigured) SecureSessionState.NOT_AUTHENTICATED else SecureSessionState.NOT_CONFIGURED,
                protocolConfigured = protocolConfigured,
            )
            addEvent(
                EventCategory.SYSTEM,
                "BLE SERVICES READY",
                when {
                    protocolConfigured -> "SecureMesh GATT contract detected; authentication pending"
                    serviceDetected -> "SecureMesh service candidate detected, but characteristics/codec are not configured"
                    else -> "BLE connected; SecureMesh protocol UUID not configured/detected"
                },
            )
        }
    }

    private fun environmentState(): MeshConnectionState {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return MeshConnectionState.BluetoothUnavailable
        val a = adapter ?: return MeshConnectionState.BluetoothUnavailable
        if (!a.isEnabled) return MeshConnectionState.BluetoothDisabled
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return MeshConnectionState.PermissionRequired(missing)
        return MeshConnectionState.Idle
    }

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun stopScanInternal(updateState: Boolean) {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            if (updateState) _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
            return
        } catch (_: Throwable) {
            // Scanner may already be gone while Bluetooth is turning off.
        }
        if (updateState && (_connectionState.value is MeshConnectionState.Scanning || _connectionState.value is MeshConnectionState.DeviceFound)) {
            _connectionState.value = MeshConnectionState.Idle
            addEvent(EventCategory.RADIO, "BLE SCAN STOPPED", "${_discoveredDevices.value.size} devices found")
        }
    }

    private fun closeGatt() {
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
    }

    private fun addEvent(category: EventCategory, title: String, details: String) {
        val event = MeshEvent("BLE-${now()}-${_events.value.size}", now(), category, title, details)
        _events.value = (listOf(event) + _events.value).take(200)
    }
}
