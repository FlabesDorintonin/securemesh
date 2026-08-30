package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.DeviceClassification
import java.util.UUID

data class AdvertisementSnapshot(
    val advertisedName: String?,
    val serviceUuids: Set<UUID>,
    val manufacturerData: Map<Int, ByteArray>,
)

data class DeviceMatch(
    val classification: DeviceClassification,
    val protocolVersion: Int? = null,
    val deviceType: String? = null,
    val reasons: Set<String> = emptySet(),
)

class SecureMeshDeviceMatcher(private val config: BleProtocolConfig) {
    fun match(snapshot: AdvertisementSnapshot): DeviceMatch {
        val reasons = linkedSetOf<String>()
        val hasService = config.serviceUuid in snapshot.serviceUuids
        if (hasService) reasons += "service-uuid"

        val nameLooksLikeSecureMesh = snapshot.advertisedName?.startsWith("SecureMesh", ignoreCase = true) == true
        if (nameLooksLikeSecureMesh) reasons += "name-only-not-identity"

        // Protocol v0.2 explicitly defines the advertised Service UUID as the discovery marker.
        // The device name is deliberately not sufficient evidence of SecureMesh identity.
        return if (hasService) {
            DeviceMatch(DeviceClassification.SECUREMESH_CANDIDATE, reasons = reasons)
        } else {
            DeviceMatch(DeviceClassification.UNKNOWN_BLE, reasons = reasons)
        }
    }
}
