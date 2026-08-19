package dev.securemesh.commander.data.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.securemesh.commander.domain.model.BondStatus
import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.model.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface BleProximitySource {
    val devices: StateFlow<List<DiscoveredDevice>>
    val scanning: StateFlow<Boolean>
    val error: StateFlow<String?>
    fun requiredPermissions(): List<String>
    fun start(durationMs: Long = 12_000L): Result<Unit>
    fun stop(updateState: Boolean = true)
}

object NoopBleProximitySource : BleProximitySource {
    private val emptyDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    private val idle = MutableStateFlow(false)
    private val noError = MutableStateFlow<String?>(null)
    override val devices: StateFlow<List<DiscoveredDevice>> = emptyDevices.asStateFlow()
    override val scanning: StateFlow<Boolean> = idle.asStateFlow()
    override val error: StateFlow<String?> = noError.asStateFlow()
    override fun requiredPermissions(): List<String> = emptyList()
    override fun start(durationMs: Long): Result<Unit> = Result.success(Unit)
    override fun stop(updateState: Boolean) = Unit
}

/**
 * Advertisement-only scanner used by BLE Radar. It never opens a GATT session
 * and therefore does not disturb the primary SecureMesh transport connection.
 */
class BleProximityScanner(context: Context) : BleProximitySource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = manager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = linkedMapOf<String, DiscoveredDevice>()
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private var timeoutJob: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
        override fun onScanFailed(errorCode: Int) {
            timeoutJob?.cancel(); timeoutJob = null
            _scanning.value = false
            _error.value = "BLE scan failed: $errorCode"
        }
    }

    override fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
    }

    override fun start(durationMs: Long): Result<Unit> = runCatching {
        val missing = missingPermissions()
        require(missing.isEmpty()) { "BLE_PERMISSION:${missing.joinToString(",")}" }
        val localAdapter = requireNotNull(adapter) { "Bluetooth LE unavailable" }
        require(localAdapter.isEnabled) { "Bluetooth disabled" }
        val scanner = requireNotNull(localAdapter.bluetoothLeScanner) { "BluetoothLeScanner unavailable" }
        stop(updateState = false)
        purge(System.currentTimeMillis())
        _error.value = null
        _scanning.value = true
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (error: SecurityException) {
            throw IllegalStateException("BLE_PERMISSION: permission was revoked during scan start", error)
        }
        timeoutJob = scope.launch {
            delay(durationMs.coerceIn(5_000L, 30_000L))
            stop(updateState = true)
        }
    }.onFailure {
        _scanning.value = false
        _error.value = it.message
    }

    override fun stop(updateState: Boolean) {
        timeoutJob?.cancel(); timeoutJob = null
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { }
        purge(System.currentTimeMillis())
        publish()
        if (updateState) _scanning.value = false
    }

    private fun accept(result: ScanResult) {
        val now = System.currentTimeMillis()
        val address = try { result.device.address } catch (_: SecurityException) { return }
        val services = result.scanRecord?.serviceUuids?.map { it.uuid }?.toSet().orEmpty()
        val exactService = BleProtocolConfig.ProtocolV02.serviceUuid in services
        val advertisedName = result.scanRecord?.deviceName?.takeIf(String::isNotBlank)
        val classification = if (exactService) DeviceClassification.SECUREMESH_CANDIDATE else DeviceClassification.UNKNOWN_BLE
        cache[address] = DiscoveredDevice(
            address = address,
            advertisedName = advertisedName,
            rssi = result.rssi,
            lastSeenEpochMs = now,
            classification = classification,
            bondStatus = BondStatus.UNKNOWN,
        )
        purge(now)
        publish()
    }

    private fun purge(now: Long) {
        cache.entries.removeAll { now - it.value.lastSeenEpochMs > 15_000L }
    }

    private fun publish() {
        _devices.value = cache.values.sortedWith(
            compareByDescending<DiscoveredDevice> { it.classification != DeviceClassification.UNKNOWN_BLE }
                .thenByDescending { it.rssi }
        )
    }
}
