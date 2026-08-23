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
        if (mask and (1L shl 6) != 0L) add(DeviceCapability.ROUTING)
        if (mask and (1L shl 9) != 0L) add(DeviceCapability.GPS)
        if (mask and (1L shl 10) != 0L) add(DeviceCapability.SOS)
        if (mask and ((1L shl 13) or (1L shl 14)) != 0L) add(DeviceCapability.NETWORK_DIAGNOSTICS)
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
        if (mask and (1L shl 4) != 0L) {
            add(SessionPermission.VIEW_OWN_POSITION)
            add(SessionPermission.VIEW_TEAM_POSITIONS)
        }
        if (mask and (1L shl 5) != 0L) {
            add(SessionPermission.VIEW_SOS)
            add(SessionPermission.ACKNOWLEDGE_SOS)
        }
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
        2, 3 -> MeshRoute(route.destination, route.nextHop, RouteType.DYNAMIC, updatedAtEpochMs = nowMs)
        4 -> MeshRoute(route.destination, route.nextHop, RouteType.STATIC, updatedAtEpochMs = nowMs)
        else -> null
    }

    fun position(position: BlePositionPayload, nowMs: Long): NodePosition = NodePosition(
        nodeId = position.nodeId,
        latitude = position.latitudeE7 / 1e7,
        longitude = position.longitudeE7 / 1e7,
        timestampEpochMs = if (position.gpsEpochSec > 0) position.gpsEpochSec * 1000L else
            (nowMs - position.receivedAgeMs - position.fixAgeMs).coerceAtLeast(0L),
        satellites = position.satellites,
        hdop = if (position.flags and 0x08 != 0) position.hdopX100 / 100.0 else null,
        speedMps = if (position.flags and 0x04 != 0) position.speedCms / 100.0 else null,
        valid = position.hasFix,
    )

    fun sos(event: BleDecodedEvent.SosRaised, nowMs: Long): SosAlert {
        val hasPosition = event.positionAgeMs != 0xFFFF_FFFFL &&
            event.latitudeE7 in -900_000_000..900_000_000 &&
            event.longitudeE7 in -1_800_000_000..1_800_000_000
        val position = if (hasPosition) NodePosition(
            nodeId = event.origin,
            latitude = event.latitudeE7 / 1e7,
            longitude = event.longitudeE7 / 1e7,
            timestampEpochMs = (nowMs - event.positionAgeMs).coerceAtLeast(0L),
            satellites = null, hdop = null, speedMps = null, valid = event.flags and 0x01 != 0,
        ) else null
        return SosAlert(
            id = event.sosId.toString(16).uppercase().padStart(8, '0'),
            nodeId = event.origin,
            raisedAtEpochMs = if (event.raisedEpochSec > 0) event.raisedEpochSec * 1000L else nowMs,
            position = position,
            batteryPercent = event.batteryPercent,
            networkStatus = "Тревога получена через SecureMesh",
            acknowledged = false,
        )
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
