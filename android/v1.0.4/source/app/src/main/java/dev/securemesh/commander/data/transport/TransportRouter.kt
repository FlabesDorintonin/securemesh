package dev.securemesh.commander.data.transport

import dev.securemesh.commander.domain.model.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TransportRouter(
    val mock: MeshTransport,
    val ble: MeshTransport,
) {
    private val _mode = MutableStateFlow(TransportMode.BLE)
    val mode: StateFlow<TransportMode> = _mode.asStateFlow()

    fun current(): MeshTransport = if (_mode.value == TransportMode.MOCK) mock else ble

    suspend fun switchTo(mode: TransportMode) {
        if (_mode.value == mode) return
        current().stop()
        _mode.value = mode
        current().start()
    }
}
