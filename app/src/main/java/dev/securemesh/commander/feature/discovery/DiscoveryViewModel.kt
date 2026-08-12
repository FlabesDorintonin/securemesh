package dev.securemesh.commander.feature.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val connection: MeshConnectionState = MeshConnectionState.Idle,
    val session: SecureMeshSession? = null,
    val filter: DiscoveryFilter = DiscoveryFilter(),
)

class DiscoveryViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val filter = MutableStateFlow(DiscoveryFilter())

    /** Raw scanner health is intentionally visible during the first hardware integration tests. */
    val diagnostics = repository.bleDiagnostics

    val uiState = combine(
        repository.discoveredDevices,
        repository.connectionState,
        repository.session,
        filter,
    ) { devices, connection, session, f ->
        // Discovery is observability, not authentication. The default list receives every real
        // ScanResult. Only the explicit user-controlled "Только SecureMesh" filter narrows it.
        DiscoveryUiState(
            devices = filterDevices(devices, f),
            connection = connection,
            session = session,
            filter = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryUiState())

    fun setQuery(value: String) { filter.value = filter.value.copy(query = value) }
    fun setSecureMeshOnly(value: Boolean) { filter.value = filter.value.copy(secureMeshOnly = value) }
    fun setSort(value: DeviceSort) { filter.value = filter.value.copy(sort = value) }
    fun scan() = viewModelScope.launch { repository.scanDevices() }
    fun stopScan() = viewModelScope.launch { repository.stopScan() }
    fun refresh() = viewModelScope.launch { repository.stopScan(); repository.scanDevices() }
    fun connect(device: DiscoveredDevice) = viewModelScope.launch { repository.connect(device) }
    fun disconnect() = viewModelScope.launch { repository.disconnect() }
}
