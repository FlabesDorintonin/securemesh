package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.DiscoveredDevice
import kotlinx.coroutines.flow.StateFlow

sealed interface PairingState {
    data object Idle : PairingState
    data class CodeRequired(val device: DiscoveredDevice, val expiresAtEpochMs: Long) : PairingState
    data object Verifying : PairingState
    data object Established : PairingState
    data class Error(val message: String) : PairingState
}

interface PairingController {
    val state: StateFlow<PairingState>
    suspend fun begin(device: DiscoveredDevice)
    suspend fun submitCode(code: String): Result<Unit>
    suspend fun cancel()
}
