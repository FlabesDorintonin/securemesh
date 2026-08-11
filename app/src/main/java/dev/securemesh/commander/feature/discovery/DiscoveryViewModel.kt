package dev.securemesh.commander.feature.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.DiscoveredDevice
import dev.securemesh.commander.domain.model.MeshConnectionState
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val connection: MeshConnectionState = MeshConnectionState.Idle,
    val filter: DiscoveryFilter = DiscoveryFilter(),
    val showUnknown: Boolean = true,
)

class DiscoveryViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val filter = MutableStateFlow(DiscoveryFilter())
    val uiState = combine(repository.discoveredDevices, repository.connectionState, filter, repository.settings) { devices, connection, f, settings ->
        val allowed = if (settings.showUnknownBle) devices else devices.filter { it.classification != dev.securemesh.commander.domain.model.DeviceClassification.UNKNOWN_BLE }
        DiscoveryUiState(filterDevices(allowed, f), connection, f, settings.showUnknownBle)
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
