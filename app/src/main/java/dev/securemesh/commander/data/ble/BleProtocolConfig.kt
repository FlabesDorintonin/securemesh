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
        /** Exact contract from SecureMesh_BLE_Protocol_v0_1.md. */
        val ProtocolV01 = BleProtocolConfig(
            serviceUuid = UUID.fromString("7b7f0001-6b6f-4d65-7368-534543555245"),
            infoCharacteristicUuid = UUID.fromString("7b7f0002-6b6f-4d65-7368-534543555245"),
            commandCharacteristicUuid = UUID.fromString("7b7f0003-6b6f-4d65-7368-534543555245"),
            responseCharacteristicUuid = UUID.fromString("7b7f0004-6b6f-4d65-7368-534543555245"),
            eventCharacteristicUuid = UUID.fromString("7b7f0005-6b6f-4d65-7368-534543555245"),
            supportedProtocolVersions = setOf(1),
            preferredMtu = 185,
            maxApplicationPacketBytes = 384,
            reassemblyTimeoutMs = 3_000L,
            showAllBleDevices = true,
        )

        val Development = ProtocolV01
    }
}
