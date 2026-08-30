package dev.securemesh.commander.feature.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.BleRadarState
import dev.securemesh.commander.domain.model.DeviceCapability
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BleRadarUiState(
    val supported: Boolean = false,
    val radar: BleRadarState? = null,
    val clearing: Boolean = false,
    val error: String? = null,
)

class BleRadarViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val action = kotlinx.coroutines.flow.MutableStateFlow<Pair<Boolean, String?>>(false to null)
    val uiState = combine(repository.session, repository.bleDiagnostics, action) { session, diagnostics, actionState ->
        BleRadarUiState(
            supported = session?.supports(DeviceCapability.BLE_RADAR) == true,
            radar = diagnostics?.radar,
            clearing = actionState.first,
            error = actionState.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BleRadarUiState())

    fun clear() = viewModelScope.launch {
        action.value = true to null
        val result = repository.clearBleRadar()
        action.value = false to result.exceptionOrNull()?.message
    }
}
