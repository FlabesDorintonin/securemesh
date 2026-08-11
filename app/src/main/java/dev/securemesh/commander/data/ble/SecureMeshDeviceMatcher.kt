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
        var protocolEvidence = false

        config.serviceUuid?.let { uuid ->
            if (uuid in snapshot.serviceUuids) {
                protocolEvidence = true
                reasons += "service-uuid"
            }
        }
        config.manufacturerId?.let { id ->
            if (snapshot.manufacturerData.containsKey(id)) {
                protocolEvidence = true
                reasons += "manufacturer-data"
            }
        }
        val developmentNameMatch = snapshot.advertisedName?.let { name ->
            config.developmentNamePrefixes.any { prefix -> name.startsWith(prefix, ignoreCase = true) }
        } == true
        if (developmentNameMatch) reasons += "development-name-only"

        // Name is intentionally weak evidence. KNOWN/TRUSTED require persisted identity or protocol handshake later.
        return when {
            protocolEvidence -> DeviceMatch(DeviceClassification.SECUREMESH_CANDIDATE, reasons = reasons)
            developmentNameMatch -> DeviceMatch(DeviceClassification.SECUREMESH_CANDIDATE, reasons = reasons)
            else -> DeviceMatch(DeviceClassification.UNKNOWN_BLE)
        }
    }
}
