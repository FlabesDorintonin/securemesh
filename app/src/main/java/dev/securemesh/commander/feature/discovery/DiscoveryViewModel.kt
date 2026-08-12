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
    val showUnknown: Boolean = false,
)

class DiscoveryViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val filter = MutableStateFlow(DiscoveryFilter())
    val uiState = combine(repository.discoveredDevices, repository.connectionState, repository.session, filter, repository.settings) { devices, connection, session, f, settings ->
        val canShowUnknown = settings.developerMode && settings.showUnknownBle
        val allowed = if (canShowUnknown) devices else devices.filter { it.classification != DeviceClassification.UNKNOWN_BLE }
        DiscoveryUiState(filterDevices(allowed, f), connection, session, f, canShowUnknown)
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
