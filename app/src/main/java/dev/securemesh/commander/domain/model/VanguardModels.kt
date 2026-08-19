package dev.securemesh.commander.domain.model

data class VanguardManifestEntry(
    val slot: Int,
    val nodeId: NodeId,
)

data class VanguardManifest(
    val valid: Boolean,
    val networkEpoch: Long,
    val digest: Long,
    val entries: List<VanguardManifestEntry>,
) {
    val nodes: List<NodeId> get() = entries.sortedBy { it.slot }.map { it.nodeId }
}

data class VanguardRouteDetail(
    val destination: NodeId,
    val primaryNextHop: NodeId?,
    val backupNextHop: NodeId?,
    val alternateNextHop: NodeId?,
    val generationBootEpoch: Long,
    val generationRouteSeq: Long,
    val guardRank: Long,
    val feasibleDistance: Long,
    val primaryInternalMask: Long,
    val backupInternalMask: Long,
    val primaryPathTag: Long,
    val backupPathTag: Long,
    val primaryEca: Double,
    val primaryReliability: Double,
    val primaryExact: Boolean,
    val exactG2Available: Boolean,
    val primaryPromotedFromBackup: Boolean,
    val primaryPathTagged: Boolean,
    val backupPathTagged: Boolean,
    val backupLease: Int,
)

data class VanguardDiagnostics(
    val version: Int,
    val manifestValid: Boolean,
    val networkEpoch: Long,
    val manifestDigest: Long,
    val localRouteSeq: Long,
    val acceptedPrimary: Long,
    val acceptedBackup: Long,
    val acceptedAlternate: Long,
    val rejectedOldGeneration: Long,
    val rejectedLoop: Long,
    val rejectedInfeasible: Long,
    val rejectedWorse: Long,
    val rejectedSamePath: Long,
    val promotionsG2: Long,
    val promotionsAlternate: Long,
    val expirations: Long,
    val routeErrors: Long,
    val controlBudgetDrops: Long,
    val controlBudgetTokensUs: Long,
    val deferredQueued: Long,
    val deferredDrops: Long,
    val activeDeferred: Int,
    val labFaultRxDrops: Long,
    val labFaultTxDrops: Long,
    val activeLabFaults: Int,
    val routes: List<VanguardRouteDetail>,
    val updatedAtEpochMs: Long,
)

data class LabLinkPolicy(
    val peerNodeId: NodeId,
    val block: Boolean,
    val metricOverride: Boolean,
    val remainingMs: Long,
    val reliability: Double,
    val eca: Double,
) {
    val manual: Boolean get() = remainingMs == 0xFFFF_FFFFL
}

enum class LabLinkPreset {
    CLEAR,
    BLOCK,
    SOFT_WEAK,
    VERY_WEAK,
}

enum class VanguardRuntimeEventType(val wire: Int) {
    DISCOVERY_STARTED(1),
    DISCOVERY_RETRY(2),
    ROUTE_READY(3),
    G2_READY(4),
    G2_UNAVAILABLE(5),
    ROUTE_PROMOTED_G2(6),
    ROUTE_PROMOTED_ALTERNATE(7),
    ROUTE_LOST(8),
    DISCOVERY_FAILED(9),
    UNKNOWN(-1);

    companion object {
        fun fromWire(value: Int): VanguardRuntimeEventType = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

data class VanguardRuntimeEvent(
    val transportEventWire: Int,
    val runtimeType: VanguardRuntimeEventType,
    val destination: NodeId,
    val nextHop: NodeId?,
    val requestIdOrPathTag: Long,
    val routeVersion: Long,
    val timestampEpochMs: Long,
)
