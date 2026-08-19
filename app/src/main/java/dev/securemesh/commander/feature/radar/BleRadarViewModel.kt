package dev.securemesh.commander.feature.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.BleProximityAssessment
import dev.securemesh.commander.domain.service.assessBleProximity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** BLE Radar is presentation only; Android scanner/GATT APIs remain in data layer. */
data class RadarDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val smoothedRssi: Int,
    val previousSmoothedRssi: Int?,
    val lastSeenEpochMs: Long,
    val secureMesh: Boolean,
) {
    val proximity: BleProximityAssessment get() = assessBleProximity(smoothedRssi)
    val trend: Int get() = previousSmoothedRssi?.let { smoothedRssi - it } ?: 0
}

data class BleRadarUiState(
    val scanning: Boolean = false,
    val devices: List<RadarDevice> = emptyList(),
    val selectedAddress: String? = null,
    val permissionMissing: Boolean = false,
    val requestedPermissions: List<String> = emptyList(),
    val error: String? = null,
) {
    val selected: RadarDevice? get() = devices.firstOrNull { it.address == selectedAddress }
}

class BleRadarViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val samples = MutableStateFlow<Map<String, RadarDevice>>(emptyMap())
    private val selectedAddress = MutableStateFlow<String?>(null)
    private val permissionDenied = MutableStateFlow(false)

    val state = combine(samples, selectedAddress, repository.proximityScanning, repository.proximityScanError, permissionDenied) { devices, selected, scanning, error, denied ->
        BleRadarUiState(
            scanning = scanning,
            devices = devices.values.sortedWith(compareByDescending<RadarDevice> { it.secureMesh }.thenByDescending { it.smoothedRssi }),
            selectedAddress = selected,
            permissionMissing = denied,
            requestedPermissions = if (denied) repository.proximityScanPermissions() else emptyList(),
            error = error?.takeUnless { it.startsWith("BLE_PERMISSION:") },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BleRadarUiState())

    init {
        viewModelScope.launch {
            repository.proximityDevices.collect { discovered ->
                val now = System.currentTimeMillis()
                val previous = samples.value
                val next = discovered
                    .filter { now - it.lastSeenEpochMs <= 15_000L }
                    .associate { device ->
                        val old = previous[device.address]
                        val smoothed = if (old == null) device.rssi else (old.smoothedRssi * 0.68 + device.rssi * 0.32).roundToInt()
                        device.address to RadarDevice(
                            address = device.address,
                            name = device.advertisedName,
                            rssi = device.rssi,
                            smoothedRssi = smoothed,
                            previousSmoothedRssi = old?.smoothedRssi,
                            lastSeenEpochMs = device.lastSeenEpochMs,
                            secureMesh = device.classification != DeviceClassification.UNKNOWN_BLE,
                        )
                    }
                samples.value = next
                selectedAddress.value?.let { selected -> if (selected !in next) selectedAddress.value = null }
            }
        }
    }

    fun startScan(durationMs: Long = 12_000L) = viewModelScope.launch {
        permissionDenied.value = false
        repository.startProximityScan(durationMs.coerceIn(5_000L, 30_000L))
            .onFailure { error ->
                if (error.message?.startsWith("BLE_PERMISSION:") == true) permissionDenied.value = true
            }
    }

    fun stopScan() = viewModelScope.launch { repository.stopProximityScan() }

    fun permissionResult(grants: Map<String, Boolean>) = viewModelScope.launch {
        val ok = grants.isNotEmpty() && grants.values.all { it }
        permissionDenied.value = !ok
        if (ok) repository.startProximityScan(12_000L) else repository.stopProximityScan()
    }

    fun select(address: String?) { selectedAddress.value = address }
}
