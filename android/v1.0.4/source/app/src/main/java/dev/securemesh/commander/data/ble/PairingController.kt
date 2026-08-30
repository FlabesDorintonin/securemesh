package dev.securemesh.commander.data.ble

/**
 * SecureMesh BLE Protocol v0.2 uses Android's standard BLE Secure Connections pairing UI.
 * The six-digit passkey displayed by the ESP32 OLED is never submitted through COMMAND.
 * BleTransport observes Android bonding/authentication state directly.
 */
sealed interface SystemPairingState {
    data object Idle : SystemPairingState
    data object WaitingForSystemPasskey : SystemPairingState
    data object Authenticating : SystemPairingState
    data object Bonded : SystemPairingState
    data class Failed(val reason: String) : SystemPairingState
}
