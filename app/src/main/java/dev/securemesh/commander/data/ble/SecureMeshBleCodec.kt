package dev.securemesh.commander.data.ble

/**
 * Firmware protocol seam. The Android app must not claim SecureMesh protocol readiness until both
 * GATT identifiers and a real codec/handshake implementation exist.
 */
interface SecureMeshBleCodec {
    val configured: Boolean
    fun decodeNotification(bytes: ByteArray): Result<SecureMeshBleFrame>
    fun encodeCommand(command: SecureMeshBleCommand): Result<ByteArray>
}

sealed interface SecureMeshBleFrame {
    data class ProtocolAdvertisement(val protocolVersion: Int, val deviceType: Int) : SecureMeshBleFrame
    data class Raw(val bytes: ByteArray) : SecureMeshBleFrame
}

sealed interface SecureMeshBleCommand {
    data class RawDevelopment(val bytes: ByteArray) : SecureMeshBleCommand
}

class UnconfiguredSecureMeshBleCodec : SecureMeshBleCodec {
    override val configured: Boolean = false

    override fun decodeNotification(bytes: ByteArray): Result<SecureMeshBleFrame> =
        Result.failure(IllegalStateException("SecureMesh BLE packet codec is not configured"))

    override fun encodeCommand(command: SecureMeshBleCommand): Result<ByteArray> =
        Result.failure(IllegalStateException("SecureMesh BLE packet codec is not configured"))
}
