package dev.securemesh.commander.feature.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal fun permissionResultGranted(result: Map<String, Boolean>): Boolean =
    result.isNotEmpty() && result.values.all { it }

data class DiscoveryUiState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val connection: MeshConnectionState = MeshConnectionState.Idle,
    val session: SecureMeshSession? = null,
    val filter: DiscoveryFilter = DiscoveryFilter(),
    val permissionDenied: Boolean = false,
    val showingUnknownBle: Boolean = false,
)

class DiscoveryViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val filter = MutableStateFlow(DiscoveryFilter())
    private val permissionDenied = MutableStateFlow(false)

    val diagnostics = repository.bleDiagnostics

    val uiState = combine(
        repository.discoveredDevices,
        repository.connectionState,
        repository.session,
        repository.settings,
        combine(filter, permissionDenied) { currentFilter, denied -> currentFilter to denied },
    ) { devices, connection, session, settings, controls ->
        val (currentFilter, denied) = controls
        val canShowUnknown = settings.developerMode && settings.showUnknownBle
        DiscoveryUiState(
            devices = filterDevices(devices, currentFilter, canShowUnknown),
            connection = connection,
            session = session,
            filter = currentFilter,
            permissionDenied = denied,
            showingUnknownBle = canShowUnknown,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryUiState())

    fun setQuery(value: String) { filter.value = filter.value.copy(query = value) }
    fun setSecureMeshOnly(value: Boolean) { filter.value = filter.value.copy(secureMeshOnly = value) }
    fun setSort(value: DeviceSort) { filter.value = filter.value.copy(sort = value) }

    fun scan() = viewModelScope.launch {
        permissionDenied.value = false
        repository.scanDevices()
    }

    fun stopScan() = viewModelScope.launch { repository.stopScan() }

    fun refresh() = viewModelScope.launch {
        permissionDenied.value = false
        repository.stopScan()
        repository.scanDevices()
    }

    fun onPermissionResult(result: Map<String, Boolean>) = viewModelScope.launch {
        if (permissionResultGranted(result)) {
            permissionDenied.value = false
            repository.scanDevices()
        } else {
            permissionDenied.value = true
            repository.stopScan()
        }
    }

    fun connect(device: DiscoveredDevice) = viewModelScope.launch { repository.connect(device) }
    fun disconnect() = viewModelScope.launch { repository.disconnect() }
}
