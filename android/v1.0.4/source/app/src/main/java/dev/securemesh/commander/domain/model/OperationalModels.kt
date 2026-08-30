package dev.securemesh.commander.domain.model

enum class OperationalLevel { CRITICAL, DEGRADED, GOOD, EXCELLENT }

data class OperationalHealth(
    val score: Int,
    val level: OperationalLevel,
    val flags: Int,
    val radioScore: Int,
    val meshScore: Int,
    val routingScore: Int,
    val memoryScore: Int,
    val queueScore: Int,
    val gpsScore: Int,
    val bleScore: Int,
    val freshNeighbors: Int,
    val routeCount: Int,
    val backupRouteCount: Int,
    val queueUsed: Int,
    val queueCapacity: Int,
)

data class DeviceSelfCheck(
    val score: Int,
    val level: OperationalLevel,
    val flags: Int,
    val radioReady: Boolean,
    val protectionReady: Boolean,
    val phoneLinkReady: Boolean,
    val gpsState: Int,
    val displayReady: Boolean,
    val freshNeighbors: Int,
    val routeCount: Int,
    val backupRouteCount: Int,
    val queueUsed: Int,
    val queueCapacity: Int,
    val freeHeapBytes: Long,
    val largestHeapBlockBytes: Long,
    val successfulHopAcks: Long,
    val hopAckTimeouts: Long,
    val transmitErrors: Long,
    val radioRecoveries: Long,
    val authenticationFailures: Long,
)

data class NearbyBleDevice(
    val addressHash: Long,
    val ageMs: Long,
    val presenceMs: Long,
    val signalDbm: Int,
    val peakSignalDbm: Int,
    val signalTrendDb: Int,
    val detections: Int,
    val advertisedName: String?,
)

data class BleRadarState(
    val configured: Boolean,
    val scanning: Boolean,
    val scanCycle: Long,
    val totalDetections: Long,
    val devices: List<NearbyBleDevice>,
)
