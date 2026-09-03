package dev.securemesh.commander.data.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.securemesh.commander.data.transport.MeshTransport
import dev.securemesh.commander.domain.model.BleDiagnostics
import dev.securemesh.commander.domain.model.BondStatus
import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.model.DiscoveredDevice
import dev.securemesh.commander.domain.model.MeshConnectionState
import dev.securemesh.commander.domain.model.MeshError
import dev.securemesh.commander.domain.model.MeshErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovery compatibility layer derived from the hardware-proven SecureMesh BLE Debug app.
 *
 * It changes discovery only. Every GATT, pairing, Protocol v0.2, command, request and event
 * operation is still handled by the existing [BleTransport]. Seeing an advertisement never
 * creates identity/trust: exact GATT service + authenticated INFO/nodeId remain mandatory.
 */
class BleDiscoveryParityTransport(
    private val context: Context,
    private val delegate: BleTransport = BleTransport(context),
    private val config: BleProtocolConfig = BleProtocolConfig.ProtocolV02,
    private val now: () -> Long = System::currentTimeMillis,
) : MeshTransport by delegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val matcher = SecureMeshDeviceMatcher(config)
    private val scanResults = ConcurrentHashMap<String, DiscoveredDevice>()

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Idle)
    override val connectionState = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _bleDiagnostics = MutableStateFlow<BleDiagnostics?>(BleDiagnostics())
    override val bleDiagnostics = _bleDiagnostics.asStateFlow()

    @Volatile private var scanning = false
    private var scanTimeoutJob: Job? = null
    private var scanEndsAtEpochMs: Long = 0L
    private var rawCallbackCount: Int = 0
    private var callbackParseErrors: Int = 0
    private var lastScanSummary: String? = null

    init {
        scope.launch {
            delegate.connectionState.collect { state ->
                if (!scanning) _connectionState.value = state
            }
        }
        scope.launch {
            delegate.discoveredDevices.collect { devices ->
                if (scanning) return@collect
                devices.forEach { device -> scanResults[device.address] = device }
                if (devices.isNotEmpty()) _discoveredDevices.value = scanResults.values.sortedByDescending { it.rssi }
            }
        }
        scope.launch {
            delegate.bleDiagnostics.collect { diagnostics ->
                if (scanning) return@collect
                _bleDiagnostics.value = diagnostics?.let { current ->
                    if (current.lastResponse == null && lastScanSummary != null) current.copy(lastResponse = lastScanSummary) else current
                }
            }
        }
    }

    override suspend fun start() {
        delegate.start()
        _connectionState.value = delegate.connectionState.value
        _bleDiagnostics.value = delegate.bleDiagnostics.value
    }

    override suspend fun stop() {
        stopOwnScan(updateState = false)
        delegate.stop()
        _connectionState.value = delegate.connectionState.value
        _bleDiagnostics.value = delegate.bleDiagnostics.value
    }

    override suspend fun startScan(durationMs: Long) {
        when (val environment = environmentState()) {
            MeshConnectionState.Idle -> Unit
            else -> {
                scanning = false
                _connectionState.value = environment
                return
            }
        }

        // Match the known-good debug app: obtain the current scanner and call the simplest
        // unfiltered startScan(callback) overload. Do not inject ScanSettings or ScanFilter.
        if (scanning) stopOwnScan(updateState = false)
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            scanning = false
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.SCAN_FAILED, "BluetoothLeScanner unavailable", "BLE-сканер недоступен")
            )
            return
        }

        val boundedDuration = durationMs.coerceIn(5_000L, 30_000L)
        val startedAt = now()
        scanEndsAtEpochMs = startedAt + boundedDuration
        rawCallbackCount = 0
        callbackParseErrors = 0
        lastScanSummary = "scan started · default unfiltered Android API"
        scanResults.clear()
        _discoveredDevices.value = emptyList()
        scanning = true
        _connectionState.value = MeshConnectionState.Scanning(startedAt, scanEndsAtEpochMs)
        updateScanDiagnostics("callbacks=0 · unique=0 · parseErrors=0")

        try {
            scanner.startScan(scanCallback)
            scanTimeoutJob = scope.launch {
                delay(boundedDuration)
                stopOwnScan(updateState = true)
            }
        } catch (_: SecurityException) {
            scanning = false
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
            updateScanDiagnostics("permission rejected while starting scan")
        } catch (t: Throwable) {
            scanning = false
            val technical = "startScan failed: ${t.message ?: t::class.java.simpleName}"
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.SCAN_FAILED, technical, "Не удалось запустить BLE-поиск")
            )
            updateScanDiagnostics(technical)
        }
    }

    override suspend fun stopScan() = stopOwnScan(updateState = true)

    override suspend fun connect(device: DiscoveredDevice) {
        stopOwnScan(updateState = false)
        scanning = false
        delegate.connect(device)
        _connectionState.value = delegate.connectionState.value
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            rawCallbackCount++
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                rawCallbackCount++
                handleScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val technical = "Android onScanFailed=$errorCode after $rawCallbackCount callbacks"
            lastScanSummary = technical
            _connectionState.value = MeshConnectionState.Error(
                MeshError(MeshErrorCode.SCAN_FAILED, technical, "BLE-поиск завершился ошибкой ($errorCode)")
            )
            updateScanDiagnostics(technical)
        }
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            val device = result.device
            val address = device.address
            val previous = scanResults[address]
            val record = result.scanRecord
            val name = record?.deviceName ?: device.name ?: previous?.advertisedName
            val services = record?.serviceUuids?.map { it.uuid }?.toSet().orEmpty()
            val manufacturer = buildMap<Int, ByteArray> {
                val values = record?.manufacturerSpecificData ?: return@buildMap
                for (index in 0 until values.size()) put(values.keyAt(index), values.valueAt(index))
            }
            val currentMatch = matcher.match(AdvertisementSnapshot(name, services, manufacturer))

            // Advertising packet and scan response may arrive separately. Preserve all evidence
            // seen for the same transport address instead of letting a later partial callback
            // downgrade a previously observed SecureMesh Service UUID.
            val reasons = previous?.matchReasons.orEmpty() + currentMatch.reasons
            val classification = when {
                previous?.classification == DeviceClassification.TRUSTED_SECUREMESH -> DeviceClassification.TRUSTED_SECUREMESH
                previous?.classification == DeviceClassification.KNOWN_SECUREMESH -> DeviceClassification.KNOWN_SECUREMESH
                previous?.classification == DeviceClassification.SECUREMESH_CANDIDATE ||
                    currentMatch.classification == DeviceClassification.SECUREMESH_CANDIDATE -> DeviceClassification.SECUREMESH_CANDIDATE
                else -> DeviceClassification.UNKNOWN_BLE
            }

            scanResults[address] = DiscoveredDevice(
                address = address,
                advertisedName = name,
                rssi = result.rssi,
                lastSeenEpochMs = now(),
                classification = classification,
                bondStatus = device.bondState.toBondStatus(),
                secureMeshNodeId = previous?.secureMeshNodeId,
                protocolVersion = currentMatch.protocolVersion ?: previous?.protocolVersion,
                deviceType = currentMatch.deviceType ?: previous?.deviceType,
                matchReasons = reasons,
            )
            _discoveredDevices.value = scanResults.values.sortedByDescending { it.rssi }
            _connectionState.value = MeshConnectionState.DeviceFound(_discoveredDevices.value.size, scanEndsAtEpochMs)
            updateScanDiagnostics(
                "callbacks=$rawCallbackCount · unique=${scanResults.size} · parseErrors=$callbackParseErrors · last=${name ?: "Unknown"} $address ${result.rssi}dBm"
            )
        } catch (_: SecurityException) {
            scanning = false
            _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
            updateScanDiagnostics("permission rejected inside scan callback")
        } catch (t: Throwable) {
            callbackParseErrors++
            updateScanDiagnostics(
                "callbacks=$rawCallbackCount · unique=${scanResults.size} · parseErrors=$callbackParseErrors · ${t::class.java.simpleName}"
            )
        }
    }

    private fun stopOwnScan(updateState: Boolean) {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val wasScanning = scanning
        if (wasScanning) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                scanning = false
                if (updateState) _connectionState.value = MeshConnectionState.PermissionRequired(requiredPermissions())
                return
            } catch (_: Throwable) {
                // stopScan is cleanup; keep the scan summary visible.
            }
        }
        scanning = false
        if (wasScanning) {
            val summary = "scan complete · callbacks=$rawCallbackCount · unique=${scanResults.size} · parseErrors=$callbackParseErrors"
            lastScanSummary = summary
            updateScanDiagnostics(summary, gattState = "IDLE")
        }
        if (updateState && (_connectionState.value is MeshConnectionState.Scanning || _connectionState.value is MeshConnectionState.DeviceFound)) {
            _connectionState.value = delegate.connectionState.value
        }
    }

    private fun environmentState(): MeshConnectionState {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return MeshConnectionState.BluetoothUnavailable
        }

        // Permission-first ordering is intentional. On Android 12+ some OEM Bluetooth
        // stacks throw SecurityException from adapter state access before Nearby devices
        // has been granted. Never touch isEnabled/scanner until the permission contract
        // is satisfied.
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) return MeshConnectionState.PermissionRequired(missing)

        val currentAdapter = adapter ?: return MeshConnectionState.BluetoothUnavailable
        return try {
            if (currentAdapter.isEnabled) MeshConnectionState.Idle else MeshConnectionState.BluetoothDisabled
        } catch (_: SecurityException) {
            MeshConnectionState.PermissionRequired(requiredPermissions())
        } catch (t: Throwable) {
            MeshConnectionState.Error(
                MeshError(
                    MeshErrorCode.BLUETOOTH_UNAVAILABLE,
                    "Bluetooth adapter state failed: ${t.message ?: t::class.java.simpleName}",
                    "Не удалось проверить состояние Bluetooth",
                )
            )
        }
    }

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun updateScanDiagnostics(summary: String, gattState: String = "SCANNING_DEFAULT_UNFILTERED") {
        lastScanSummary = summary
        val base = delegate.bleDiagnostics.value ?: BleDiagnostics()
        _bleDiagnostics.value = base.copy(gattState = gattState, lastResponse = summary)
    }

    private fun Int.toBondStatus(): BondStatus = when (this) {
        BluetoothDevice.BOND_BONDED -> BondStatus.BONDED
        BluetoothDevice.BOND_BONDING -> BondStatus.BONDING
        BluetoothDevice.BOND_NONE -> BondStatus.NOT_BONDED
        else -> BondStatus.UNKNOWN
    }
}
