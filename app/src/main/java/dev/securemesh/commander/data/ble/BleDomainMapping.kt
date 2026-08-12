package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.*
import kotlin.math.roundToInt

object SecureMeshBleV01DomainMapping {
    fun role(raw: Int): NodeRole = when (raw) {
        1 -> NodeRole.DEVELOPMENT
        else -> NodeRole.UNKNOWN
    }

    fun capabilities(mask: Long): Set<DeviceCapability> = buildSet {
        if (mask and (1L shl 0) != 0L) add(DeviceCapability.MESSAGING)
        if (mask and (1L shl 1) != 0L) add(DeviceCapability.STATIC_ROUTING)
        if (mask and (1L shl 2) != 0L) add(DeviceCapability.RELAY)
        if (mask and (1L shl 3) != 0L) add(DeviceCapability.FIELD_TEST)
        if (mask and (1L shl 4) != 0L) add(DeviceCapability.BLE_CONTROL)
    }

    /**
     * v0.6 permission bits are development placeholders. This projection only controls what Android
     * offers in UI; firmware remains the authorization boundary for every command.
     */
    fun permissions(mask: Long): Set<SessionPermission> = buildSet {
        if (mask and (1L shl 0) != 0L) {
            add(SessionPermission.VIEW_OWN_NODE)
            add(SessionPermission.VIEW_NODES)
            add(SessionPermission.VIEW_NETWORK_TOPOLOGY)
            add(SessionPermission.VIEW_ROUTES)
            add(SessionPermission.VIEW_MESSAGES)
            add(SessionPermission.VIEW_SYSTEM_LOG)
            add(SessionPermission.VIEW_NETWORK_DIAGNOSTICS)
        }
        if (mask and (1L shl 1) != 0L) add(SessionPermission.SEND_MESSAGE)
        if (mask and (1L shl 2) != 0L) add(SessionPermission.MANAGE_ROUTES)
        if (mask and (1L shl 3) != 0L) add(SessionPermission.RUN_FIELD_TEST)
    }

    fun identity(info: BleInfoPayload): NodeIdentity = NodeIdentity(
        nodeId = info.localNodeId,
        // v0.1 INFO intentionally does not expose a user-assigned display name.
        displayName = "Узел ${info.localNodeId}",
        role = role(info.deviceRole),
        firmwareVersion = info.firmwareVersion,
        protocolVersion = info.bleProtocolVersion,
        capabilities = capabilities(info.capabilityMask),
    )

    fun neighborNode(neighbor: BleNeighborPayload, nowMs: Long): MeshNode = MeshNode(
        identity = NodeIdentity(
            nodeId = neighbor.nodeId,
            displayName = "Узел ${neighbor.nodeId}",
            role = NodeRole.UNKNOWN,
            firmwareVersion = null,
            protocolVersion = null,
            capabilities = emptySet(),
        ),
        online = neighbor.fresh,
        lastSeenEpochMs = (nowMs - neighbor.lastSeenAgeMs).coerceAtLeast(0L),
    )

    fun neighborLink(localNodeId: NodeId, neighbor: BleNeighborPayload, nowMs: Long): MeshLink = MeshLink(
        fromNode = localNodeId,
        toNode = neighbor.nodeId,
        rssi = neighbor.rssiDbm.roundToInt(),
        snr = neighbor.snrDb,
        // HELLO receive PDR is the only general neighbor receive-PDR metric in this payload.
        pdr = neighbor.helloPdr,
        retries = null,
        lastSeenEpochMs = (nowMs - neighbor.lastSeenAgeMs).coerceAtLeast(0L),
    )

    fun route(route: BleRoutePayload, nowMs: Long): MeshRoute? = when (route.source) {
        1 -> MeshRoute(route.destination, route.nextHop, RouteType.DIRECT, updatedAtEpochMs = nowMs)
        2 -> MeshRoute(route.destination, route.nextHop, RouteType.STATIC, updatedAtEpochMs = nowMs)
        else -> null
    }

    fun fieldTest(
        status: BleFieldTestStatusPayload,
        localNodeId: NodeId,
        previous: FieldTestSession?,
        nowMs: Long,
    ): FieldTestSession? {
        if (status.state == 0 || status.testId == 0L) return null
        val previousConfig = previous?.takeIf { it.id == status.testId.toString() }?.config
        val config = FieldTestConfig(
            source = localNodeId,
            target = status.target,
            mode = if (status.mode == 1) FieldTestMode.DIRECT else FieldTestMode.ROUTED,
            packetCount = status.requestedPackets,
            // GET_FIELD_TEST_STATUS does not carry these two original request fields.
            intervalMs = previousConfig?.intervalMs ?: 0L,
            payloadBytes = previousConfig?.payloadBytes ?: 0,
        )
        val running = status.state == 1
        val finishedAt = if (running) null else nowMs
        return FieldTestSession(
            id = status.testId.toString(),
            config = config,
            startedAtEpochMs = previous?.takeIf { it.id == status.testId.toString() }?.startedAtEpochMs
                ?: (nowMs - status.elapsedMs).coerceAtLeast(0L),
            finishedAtEpochMs = finishedAt,
            sent = status.sentProbes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            confirmedReceived = status.endToEndReplies.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            confirmedLost = status.endToEndTimeouts.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            retries = status.firstHopRetryTimeouts.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            route = emptyList(),
            points = previous?.takeIf { it.id == status.testId.toString() }?.points.orEmpty(),
            running = running,
            firstHopAcked = status.firstHopAcked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            firstHopFailures = status.firstHopFinalFailures.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rttAverageMs = status.averageRttMs,
            rttMinimumMs = status.minimumRttMs,
            rttMaximumMs = status.maximumRttMs,
            currentNextHop = status.firstNextHop.takeUnless { it == "00000000" },
            averageFirstHopRssiDbm = status.averageFirstHopRssiDbm,
            averageFirstHopSnrDb = status.averageFirstHopSnrDb,
        )
    }
}
