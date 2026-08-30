package dev.securemesh.commander.feature.deviceui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.DeviceCapability
import dev.securemesh.commander.domain.model.DeviceUiAction
import dev.securemesh.commander.domain.model.DeviceUiState
import dev.securemesh.commander.domain.model.OledFramebufferSnapshot
import dev.securemesh.commander.domain.model.SecureMeshSession
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceControlUiState(
    val session: SecureMeshSession? = null,
    val device: DeviceUiState? = null,
    val oledFramebuffer: OledFramebufferSnapshot? = null,
    val allowed: Boolean = false,
    val exactMirrorAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class DeviceControlViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val mirrorBusy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState = combine(
        repository.session,
        repository.deviceUiState,
        repository.oledFramebuffer,
        busy,
        error,
    ) { session, device, frame, isBusy, failure ->
        DeviceControlUiState(
            session = session,
            device = device,
            oledFramebuffer = frame,
            allowed = UiAccessPolicy.canControlDeviceUi(session),
            exactMirrorAvailable = session?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true,
            busy = isBusy,
            error = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceControlUiState())

    fun refresh() = execute { repository.refreshDeviceUiState() }

    fun refreshMirror() {
        if (mirrorBusy.value || repository.session.value?.supports(DeviceCapability.OLED_FRAMEBUFFER) != true) return
        viewModelScope.launch {
            mirrorBusy.value = true
            try { repository.refreshOledFramebuffer() } finally { mirrorBusy.value = false }
        }
    }

    fun action(action: DeviceUiAction) = execute { repository.sendDeviceUiAction(action) }

    fun clearError() {
        error.value = null
    }

    private fun execute(block: suspend () -> Result<DeviceUiState>) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                block()
                    .onSuccess { if (repository.session.value?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true) refreshMirror() }
                    .onFailure { throwable -> error.value = throwable.message ?: "Узел не принял команду" }
            } finally {
                busy.value = false
            }
        }
    }
}
