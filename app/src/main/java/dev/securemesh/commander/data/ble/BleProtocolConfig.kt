package dev.securemesh.commander.data.ble

import java.util.UUID

data class BleProtocolConfig(
    val serviceUuid: UUID? = null,
    val commandCharacteristicUuid: UUID? = null,
    val eventCharacteristicUuid: UUID? = null,
    val manufacturerId: Int? = null,
    val supportedProtocolVersions: Set<Int> = emptySet(),
    val supportedDeviceTypes: Set<Int> = emptySet(),
    val developmentNamePrefixes: List<String> = listOf("SecureMesh", "SMESH"),
    val showAllBleDevices: Boolean = true,
) {
    companion object {
        val Development = BleProtocolConfig()
    }
}
