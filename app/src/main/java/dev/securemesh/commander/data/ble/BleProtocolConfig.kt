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
         *
         * Transport fragmentation remains version 1. Application packets use version 2.
         * UUIDs, MTU target and packet limits are intentionally unchanged from the
         * original protocol surface, so the Android transport architecture remains stable.
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
            showAllBleDevices = true,
        )

        // Compatibility alias for older source files/tests. It points to the current
        // firmware contract and can be removed once the historical V01 class names
        // are retired in a later refactor.
        val ProtocolV01 = FirmwareV104
        val Development = FirmwareV104
    }
}
