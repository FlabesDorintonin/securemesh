package dev.securemesh.commander.feature.discovery

import dev.securemesh.commander.domain.model.DeviceClassification
import dev.securemesh.commander.domain.model.DiscoveredDevice

enum class DeviceSort { RSSI, NAME }

data class DiscoveryFilter(
    val query: String = "",
    val secureMeshOnly: Boolean = false,
    val sort: DeviceSort = DeviceSort.RSSI,
)

fun filterDevices(devices: List<DiscoveredDevice>, filter: DiscoveryFilter): List<DiscoveredDevice> {
    val q = filter.query.trim().lowercase()
    return devices.asSequence()
        .filter { device -> !filter.secureMeshOnly || device.classification != DeviceClassification.UNKNOWN_BLE }
        .filter { device -> q.isBlank() || device.advertisedName.orEmpty().lowercase().contains(q) || device.address.lowercase().contains(q) }
        .let { sequence ->
            when (filter.sort) {
                DeviceSort.RSSI -> sequence.sortedByDescending { it.rssi }
                DeviceSort.NAME -> sequence.sortedBy { it.advertisedName?.lowercase() ?: "~${it.address}" }
            }
        }.toList()
}
