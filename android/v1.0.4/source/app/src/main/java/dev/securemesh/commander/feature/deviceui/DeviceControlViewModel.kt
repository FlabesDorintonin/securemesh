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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    // UI actions are serialized independently from framebuffer traffic. Chunk 0
    // freezes a firmware-side 1024-byte OLED snapshot, so later chunks may safely
    // interleave with UI_ACTION without tearing the image or blocking the remote.
    private val uiStateMutex = Mutex()
    private val actionQueue = Channel<DeviceUiAction>(capacity = 16)
    private var actionMirrorJob: Job? = null

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

    init {
        viewModelScope.launch {
            for (action in actionQueue) processQueuedAction(action)
        }
    }

    fun refresh() = execute {
        uiStateMutex.withLock { repository.refreshDeviceUiState() }
    }

    fun refreshMirror() {
        if (
            busy.value ||
            mirrorBusy.value ||
            repository.session.value?.supports(DeviceCapability.OLED_FRAMEBUFFER) != true
        ) return
        viewModelScope.launch {
            mirrorBusy.value = true
            try {
                repository.refreshOledFramebuffer()
            } finally {
                mirrorBusy.value = false
            }
        }
    }

    fun action(action: DeviceUiAction) {
        if (!actionQueue.trySend(action).isSuccess) {
            error.value = "Очередь пульта заполнена — дождитесь выполнения команд"
        }
    }

    private suspend fun processQueuedAction(action: DeviceUiAction) {
        busy.value = true
        error.value = null
        val result = try {
            repository.sendDeviceUiAction(action)
        } catch (t: Throwable) {
            Result.failure(t)
        }
        result.onFailure { throwable ->
            error.value = throwable.message ?: "Узел не принял команду"
        }
        busy.value = false

        if (result.isSuccess && repository.session.value?.supports(DeviceCapability.OLED_FRAMEBUFFER) == true) {
            // Coalesce a burst of button presses into one exact framebuffer refresh.
            // The next UI_ACTION is not held behind four GET_OLED_FRAME_CHUNK calls.
            actionMirrorJob?.cancel()
            actionMirrorJob = viewModelScope.launch {
                delay(75L)
                refreshMirror()
            }
        }
    }

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
                    .onFailure { throwable -> error.value = throwable.message ?: "Узел не принял команду" }
            } finally {
                busy.value = false
            }
        }
    }
}
