package dev.securemesh.commander.data.ble

import java.util.UUID

data class BleProtocolConfig(
    val serviceUuid: UUID,
    val infoCharacteristicUuid: UUID,
    val commandCharacteristicUuid: UUID,
    val responseCharacteristicUuid: UUID,
    val eventCharacteristicUuid: UUID,
    val supportedProtocolVersions: Set<Int>,
    val preferredMtu: Int,
    val maxApplicationPacketBytes: Int,
    val reassemblyTimeoutMs: Long,
    val showAllBleDevices: Boolean = true,
) {
    companion object {
        /**
         * SecureMesh firmware v1.0.4 BLE contract.
         * Fragment transport stays v1; application packets stay v2.
         */
        val FirmwareV104 = BleProtocolConfig(
            serviceUuid = UUID.fromString("7b7f0001-6b6f-4d65-7368-534543555245"),
            infoCharacteristicUuid = UUID.fromString("7b7f0002-6b6f-4d65-7368-534543555245"),
            commandCharacteristicUuid = UUID.fromString("7b7f0003-6b6f-4d65-7368-534543555245"),
            responseCharacteristicUuid = UUID.fromString("7b7f0004-6b6f-4d65-7368-534543555245"),
            eventCharacteristicUuid = UUID.fromString("7b7f0005-6b6f-4d65-7368-534543555245"),
            supportedProtocolVersions = setOf(2),
            preferredMtu = 185,
            maxApplicationPacketBytes = 384,
            reassemblyTimeoutMs = 3_000L,
            // Discovery visibility is handled by BleDiscoveryParityTransport. This flag remains
            // true so the underlying protocol transport never discards a manually selected device.
            showAllBleDevices = true,
        )

        // Historical source names remain aliases so the full 0.9.2 UI/data stack can
        // migrate without changing the actual wire contract.
        val ProtocolV02 = FirmwareV104
        val Development = FirmwareV104
    }
}
