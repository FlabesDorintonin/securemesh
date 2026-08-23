package dev.securemesh.commander.data.ble

import dev.securemesh.commander.domain.model.*
import java.nio.charset.StandardCharsets

interface SecureMeshBleCodec {
    val configured: Boolean
    fun decodeApplicationPacket(bytes: ByteArray): Result<SecureMeshBleFrame>
    fun encodeCommand(requestId: Int, command: SecureMeshBleCommand): Result<ByteArray>
}

enum class BlePacketType(val wire: Int) {
    COMMAND(1), RESPONSE(2), EVENT(3);

    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
}

enum class BleOpcode(val wire: Int) {
    GET_INFO(1), GET_STATUS(2), GET_NEIGHBORS(3), GET_ROUTES(4), SEND_MESSAGE(5),
    ADD_STATIC_ROUTE(6), REMOVE_STATIC_ROUTE(7), START_FIELD_TEST(8), STOP_FIELD_TEST(9),
    GET_FIELD_TEST_STATUS(10), PING_LOCAL(11), CLEAR_STATS(12),
    GET_POSITIONS(24), RAISE_SOS(25), ACK_SOS(26), GET_BLE_RADAR(28), CLEAR_BLE_RADAR(29),
    GET_OPERATIONAL_HEALTH(30), GET_SELF_DIAGNOSTICS(31);

    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
}

enum class BleCommandStatus(val wire: Int) {
    OK(0), INVALID_COMMAND(1), INVALID_ARGUMENT(2), NOT_AUTHENTICATED(3), NOT_SUPPORTED(4),
    BUSY(5), NO_ROUTE(6), TX_QUEUE_FULL(7), RADIO_UNAVAILABLE(8), CRYPTO_UNAVAILABLE(9),
    TEST_ALREADY_RUNNING(10), TEST_NOT_RUNNING(11), TIMEOUT(12), INTERNAL_ERROR(13);

    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
}

enum class BleEventType(val wire: Int) {
    NODE_DISCOVERED(1), NODE_STALE(2), MESSAGE_QUEUED(3), HOP_ACK(4), RETRY(5),
    MESSAGE_LOCAL_RECEIVED(6), ROUTE_CHANGED(7), TEST_STARTED(8), TEST_PACKET_SENT(9),
    TEST_PONG_RECEIVED(10), TEST_PACKET_TIMEOUT(11), TEST_PROGRESS(12), TEST_FINISHED(13),
    RADIO_RECOVERY(14), BLE_STATE(15), ERROR(16), NO_RETURN_ROUTE(17),
    POSITION_UPDATED(28), SOS_RAISED(29), SOS_ACKNOWLEDGED(30), OPERATIONAL_HEALTH_CHANGED(32);

    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
}

sealed interface SecureMeshBleFrame {
    val requestId: Int
    val rawOpcode: Int
    val status: BleCommandStatus?
    val rawStatus: Int
    val payload: ByteArray

    data class Response(
        override val requestId: Int,
        val opcode: BleOpcode?,
        override val rawOpcode: Int,
        override val status: BleCommandStatus?,
        override val rawStatus: Int,
        override val payload: ByteArray,
    ) : SecureMeshBleFrame

    data class Event(
        val eventType: BleEventType?,
        override val rawOpcode: Int,
        override val status: BleCommandStatus?,
        override val rawStatus: Int,
        override val payload: ByteArray,
    ) : SecureMeshBleFrame {
        override val requestId: Int = 0
    }
}

sealed interface SecureMeshBleCommand {
    val opcode: BleOpcode

    data object GetInfo : SecureMeshBleCommand { override val opcode = BleOpcode.GET_INFO }
    data object GetStatus : SecureMeshBleCommand { override val opcode = BleOpcode.GET_STATUS }
    data object GetNeighbors : SecureMeshBleCommand { override val opcode = BleOpcode.GET_NEIGHBORS }
    data object GetRoutes : SecureMeshBleCommand { override val opcode = BleOpcode.GET_ROUTES }
    data class SendMessage(val destination: NodeId, val bytes: ByteArray) : SecureMeshBleCommand { override val opcode = BleOpcode.SEND_MESSAGE }
    data class AddStaticRoute(val destination: NodeId, val nextHop: NodeId) : SecureMeshBleCommand { override val opcode = BleOpcode.ADD_STATIC_ROUTE }
    data class RemoveStaticRoute(val destination: NodeId) : SecureMeshBleCommand { override val opcode = BleOpcode.REMOVE_STATIC_ROUTE }
    data class StartFieldTest(val target: NodeId, val count: Int, val intervalMs: Long, val size: Int, val directOnly: Boolean) : SecureMeshBleCommand { override val opcode = BleOpcode.START_FIELD_TEST }
    data object StopFieldTest : SecureMeshBleCommand { override val opcode = BleOpcode.STOP_FIELD_TEST }
    data object GetFieldTestStatus : SecureMeshBleCommand { override val opcode = BleOpcode.GET_FIELD_TEST_STATUS }
    data object PingLocal : SecureMeshBleCommand { override val opcode = BleOpcode.PING_LOCAL }
    data object ClearStats : SecureMeshBleCommand { override val opcode = BleOpcode.CLEAR_STATS }
    data object GetPositions : SecureMeshBleCommand { override val opcode = BleOpcode.GET_POSITIONS }
    data class RaiseSos(val type: Int = 1) : SecureMeshBleCommand { override val opcode = BleOpcode.RAISE_SOS }
    data class AckSos(val origin: NodeId, val sosId: Long) : SecureMeshBleCommand { override val opcode = BleOpcode.ACK_SOS }
    data object GetBleRadar : SecureMeshBleCommand { override val opcode = BleOpcode.GET_BLE_RADAR }
    data object ClearBleRadar : SecureMeshBleCommand { override val opcode = BleOpcode.CLEAR_BLE_RADAR }
    data object GetOperationalHealth : SecureMeshBleCommand { override val opcode = BleOpcode.GET_OPERATIONAL_HEALTH }
    data object GetSelfDiagnostics : SecureMeshBleCommand { override val opcode = BleOpcode.GET_SELF_DIAGNOSTICS }
}

data class BleInfoPayload(
    val bleProtocolVersion: Int,
    val meshWireVersion: Int,
    val messageVersion: Int,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val localNodeId: NodeId,
    val deviceRole: Int,
    val capabilityMask: Long,
    val networkId: Int,
    val bleState: Int,
    val securityFlags: Int,
    val permissionMask: Long,
) {
    val firmwareVersion: String get() = "$firmwareMajor.$firmwareMinor.$firmwarePatch"
    val physicallyConnected: Boolean get() = securityFlags and 0x01 != 0
    val authenticated: Boolean get() = securityFlags and 0x02 != 0
    val bonded: Boolean get() = securityFlags and 0x04 != 0
}

data class BleStatusPayload(
    val localNodeId: NodeId,
    val uptimeMs: Long,
    val radioReady: Boolean,
    val cryptoReady: Boolean,
    val bleState: Int,
    val freshNeighborCount: Int,
    val staticRouteCount: Int,
    val txQueueUsed: Int,
    val validAuthenticatedRadioFrames: Long,
    val transmittedRadioFrames: Long,
    val successfulHopAcks: Long,
    val hopAckTimeouts: Long,
    val authenticationFailures: Long,
    val freeHeapBytes: Long,
    val largestFreeHeapBlock: Long,
)

data class BleNeighborPayload(
    val nodeId: NodeId,
    val lastSeenAgeMs: Long,
    val rssiDbm: Double,
    val snrDb: Double,
    val helloPdr: Double,
    val hopAckPdr: Double,
    val rxFrameCount: Long,
    val txAttempts: Long,
    val successfulTxHopAcks: Long,
    val fresh: Boolean,
)

data class BleRoutePayload(val destination: NodeId, val nextHop: NodeId, val source: Int)
data class BleSendAcceptedPayload(val messageId: Long, val firstNextHop: NodeId, val routeSource: Int)

data class BleFieldTestStatusPayload(
    val state: Int,
    val mode: Int,
    val testId: Long,
    val target: NodeId,
    val elapsedMs: Long,
    val requestedPackets: Int,
    val sentProbes: Long,
    val firstHopAcked: Long,
    val firstHopFinalFailures: Long,
    val firstHopRetryTimeouts: Long,
    val endToEndReplies: Long,
    val endToEndTimeouts: Long,
    val currentSequence: Long,
    val firstNextHop: NodeId,
    val routeSource: Int,
    val averageRttMs: Long,
    val minimumRttMs: Long,
    val maximumRttMs: Long,
    val endToEndPdr: Double,
    val averageFirstHopRssiDbm: Double,
    val averageFirstHopSnrDb: Double,
)

data class BlePositionPayload(
    val nodeId: NodeId,
    val flags: Int,
    val sequence: Int,
    val gpsEpochSec: Long,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val altitudeCm: Int,
    val speedCms: Int,
    val hdopX100: Int,
    val satellites: Int,
    val fixAgeMs: Int,
    val receivedAgeMs: Long,
) {
    val hasFix: Boolean get() = flags and 0x01 != 0
}

sealed interface BleDecodedEvent {
    val type: BleEventType
    data class Node(override val type: BleEventType, val nodeId: NodeId) : BleDecodedEvent
    data class MessageQueued(val messageId: Long, val destination: NodeId, val nextHop: NodeId) : BleDecodedEvent { override val type = BleEventType.MESSAGE_QUEUED }
    data class HopAck(val messageId: Long, val neighborId: NodeId, val hopFrameCounter: Long) : BleDecodedEvent { override val type = BleEventType.HOP_ACK }
    data class Retry(val messageId: Long, val neighborId: NodeId, val attempt: Int) : BleDecodedEvent { override val type = BleEventType.RETRY }
    data class LocalMessage(val origin: NodeId, val destination: NodeId, val messageId: Long, val messageType: Int, val bytes: ByteArray) : BleDecodedEvent { override val type = BleEventType.MESSAGE_LOCAL_RECEIVED }
    data class RouteChanged(val destination: NodeId, val nextHop: NodeId, val active: Boolean) : BleDecodedEvent { override val type = BleEventType.ROUTE_CHANGED }
    data class TestStarted(val testId: Long, val target: NodeId, val requested: Int, val mode: Int) : BleDecodedEvent { override val type = BleEventType.TEST_STARTED }
    data class TestPacketSent(val testId: Long, val sequence: Long, val firstNextHop: NodeId, val sentCount: Long) : BleDecodedEvent { override val type = BleEventType.TEST_PACKET_SENT }
    data class TestPong(val testId: Long, val sequence: Long, val rttMs: Long, val replyCount: Long) : BleDecodedEvent { override val type = BleEventType.TEST_PONG_RECEIVED }
    data class TestTimeout(val testId: Long, val sequence: Long, val timeoutCount: Long) : BleDecodedEvent { override val type = BleEventType.TEST_PACKET_TIMEOUT }
    data class TestProgress(val testId: Long, val sent: Long, val replies: Long, val timeouts: Long) : BleDecodedEvent { override val type = BleEventType.TEST_PROGRESS }
    data class TestFinished(val testId: Long, val finalState: Int, val reason: BleCommandStatus?, val rawReason: Int, val sent: Long, val replies: Long, val timeouts: Long) : BleDecodedEvent { override val type = BleEventType.TEST_FINISHED }
    data class RadioRecovery(val errorCode: Int, val recoveryCount: Long) : BleDecodedEvent { override val type = BleEventType.RADIO_RECOVERY }
    data class BleState(val state: Int) : BleDecodedEvent { override val type = BleEventType.BLE_STATE }
    data class Error(val context: Int, val status: BleCommandStatus?, val rawStatus: Int, val relatedId: Long) : BleDecodedEvent { override val type = BleEventType.ERROR }
    data class NoReturnRoute(val origin: NodeId, val testId: Long, val sequence: Long) : BleDecodedEvent { override val type = BleEventType.NO_RETURN_ROUTE }
    data class PositionUpdated(val position: BlePositionPayload) : BleDecodedEvent { override val type = BleEventType.POSITION_UPDATED }
    data class SosRaised(
        val origin: NodeId, val sosId: Long, val sosType: Int, val flags: Int, val raisedEpochSec: Long,
        val latitudeE7: Int, val longitudeE7: Int, val positionAgeMs: Long, val batteryPercent: Int?,
    ) : BleDecodedEvent { override val type = BleEventType.SOS_RAISED }
    data class SosAcknowledged(val origin: NodeId, val sosId: Long, val acknowledgedBy: NodeId) : BleDecodedEvent { override val type = BleEventType.SOS_ACKNOWLEDGED }
    data class OperationalHealthChanged(val score: Int, val level: OperationalLevel, val flags: Int) : BleDecodedEvent { override val type = BleEventType.OPERATIONAL_HEALTH_CHANGED }
}

/**
 * Historical class name retained for source compatibility. The application envelope
 * implemented by this codec is SecureMesh v2 as used by firmware v1.0.4.
 */
class SecureMeshBleProtocolV01Codec : SecureMeshBleCodec {
    override val configured: Boolean = true

    override fun decodeApplicationPacket(bytes: ByteArray): Result<SecureMeshBleFrame> = runCatching {
        require(bytes.size in HEADER_SIZE..MAX_PACKET_SIZE) { "application packet size ${bytes.size} outside 10..384" }
        val r = Reader(bytes)
        require(r.u16() == MAGIC) { "wrong SecureMesh BLE magic" }
        require(r.u8() == VERSION) { "unsupported SecureMesh BLE protocol version" }
        val type = BlePacketType.fromWire(r.u8()) ?: error("invalid packet type")
        val requestId = r.u16()
        val rawOpcode = r.u8()
        val rawStatus = r.u8()
        val payloadLength = r.u16()
        require(payloadLength == bytes.size - HEADER_SIZE) { "payloadLength mismatch" }
        val payload = r.bytes(payloadLength)
        require(r.remaining == 0) { "trailing bytes" }
        when (type) {
            BlePacketType.COMMAND -> error("Android must not accept COMMAND packets from the peripheral")
            BlePacketType.RESPONSE -> SecureMeshBleFrame.Response(
                requestId = requestId,
                opcode = BleOpcode.fromWire(rawOpcode),
                rawOpcode = rawOpcode,
                status = BleCommandStatus.fromWire(rawStatus),
                rawStatus = rawStatus,
                payload = payload,
            )
            BlePacketType.EVENT -> {
                require(requestId == 0) { "event requestId must be zero" }
                require(rawStatus == BleCommandStatus.OK.wire) { "event status must be OK" }
                SecureMeshBleFrame.Event(
                    eventType = BleEventType.fromWire(rawOpcode),
                    rawOpcode = rawOpcode,
                    status = BleCommandStatus.fromWire(rawStatus),
                    rawStatus = rawStatus,
                    payload = payload,
                )
            }
        }
    }

    override fun encodeCommand(requestId: Int, command: SecureMeshBleCommand): Result<ByteArray> = runCatching {
        require(requestId in 1..0xFFFF) { "requestId must be non-zero u16" }
        val payload = encodePayload(command)
        require(payload.size <= MAX_PACKET_SIZE - HEADER_SIZE) { "command payload too large" }
        Writer(HEADER_SIZE + payload.size).apply {
            u16(MAGIC)
            u8(VERSION)
            u8(BlePacketType.COMMAND.wire)
            u16(requestId)
            u8(command.opcode.wire)
            u8(BleCommandStatus.OK.wire)
            u16(payload.size)
            bytes(payload)
        }.toByteArray()
    }

    fun parseInfo(frame: SecureMeshBleFrame.Response): Result<BleInfoPayload> = parseResponse(frame, BleOpcode.GET_INFO, 23) { r ->
        BleInfoPayload(
            bleProtocolVersion = r.u8(), meshWireVersion = r.u8(), messageVersion = r.u8(),
            firmwareMajor = r.u8(), firmwareMinor = r.u8(), firmwarePatch = r.u8(),
            localNodeId = nodeId(r.u32()), deviceRole = r.u8(), capabilityMask = r.u32(),
            networkId = r.u16(), bleState = r.u8(), securityFlags = r.u8(), permissionMask = r.u32(),
        )
    }

    fun parseStatus(frame: SecureMeshBleFrame.Response): Result<BleStatusPayload> = parseResponse(frame, BleOpcode.GET_STATUS, 42) { r ->
        BleStatusPayload(
            localNodeId = nodeId(r.u32()), uptimeMs = r.u32(), radioReady = r.u8() != 0,
            cryptoReady = r.u8() != 0, bleState = r.u8(), freshNeighborCount = r.u8(),
            staticRouteCount = r.u8(), txQueueUsed = r.u8(), validAuthenticatedRadioFrames = r.u32(),
            transmittedRadioFrames = r.u32(), successfulHopAcks = r.u32(), hopAckTimeouts = r.u32(),
            authenticationFailures = r.u32(), freeHeapBytes = r.u32(), largestFreeHeapBlock = r.u32(),
        )
    }

    fun parseNeighbors(frame: SecureMeshBleFrame.Response): Result<List<BleNeighborPayload>> = parseOk(frame, BleOpcode.GET_NEIGHBORS) { bytes ->
        val r = Reader(bytes)
        val count = r.u8()
        require(r.remaining == count * 29) { "GET_NEIGHBORS length mismatch" }
        buildList(count) {
            repeat(count) {
                add(
                    BleNeighborPayload(
                        nodeId = nodeId(r.u32()), lastSeenAgeMs = r.u32(), rssiDbm = r.i16() / 10.0,
                        snrDb = r.i16() / 10.0, helloPdr = r.u16() / 1000.0, hopAckPdr = r.u16() / 1000.0,
                        rxFrameCount = r.u32(), txAttempts = r.u32(), successfulTxHopAcks = r.u32(), fresh = r.u8() != 0,
                    )
                )
            }
        }
    }

    fun parseRoutes(frame: SecureMeshBleFrame.Response): Result<List<BleRoutePayload>> = parseOk(frame, BleOpcode.GET_ROUTES) { bytes ->
        val r = Reader(bytes)
        val count = r.u8()
        require(r.remaining == count * 9) { "GET_ROUTES length mismatch" }
        buildList(count) { repeat(count) { add(BleRoutePayload(nodeId(r.u32()), nodeId(r.u32()), r.u8())) } }
    }

    fun parseSendAccepted(frame: SecureMeshBleFrame.Response): Result<BleSendAcceptedPayload> = parseResponse(frame, BleOpcode.SEND_MESSAGE, 9) { r ->
        BleSendAcceptedPayload(r.u32(), nodeId(r.u32()), r.u8())
    }

    fun parseFieldTestStatus(frame: SecureMeshBleFrame.Response, expected: BleOpcode = BleOpcode.GET_FIELD_TEST_STATUS): Result<BleFieldTestStatusPayload> =
        parseResponse(frame, expected, 67) { r ->
            BleFieldTestStatusPayload(
                state = r.u8(), mode = r.u8(), testId = r.u32(), target = nodeId(r.u32()), elapsedMs = r.u32(),
                requestedPackets = r.u16(), sentProbes = r.u32(), firstHopAcked = r.u32(), firstHopFinalFailures = r.u32(),
                firstHopRetryTimeouts = r.u32(), endToEndReplies = r.u32(), endToEndTimeouts = r.u32(), currentSequence = r.u32(),
                firstNextHop = nodeId(r.u32()), routeSource = r.u8(), averageRttMs = r.u32(), minimumRttMs = r.u32(),
                maximumRttMs = r.u32(), endToEndPdr = r.u16() / 1000.0,
                averageFirstHopRssiDbm = r.i16() / 10.0, averageFirstHopSnrDb = r.i16() / 10.0,
            )
        }

    fun parsePositions(frame: SecureMeshBleFrame.Response): Result<List<BlePositionPayload>> =
        parseOk(frame, BleOpcode.GET_POSITIONS) { bytes ->
            val r = Reader(bytes)
            val count = r.u8()
            require(r.remaining == count * POSITION_RECORD_SIZE) { "GET_POSITIONS length mismatch" }
            buildList(count) { repeat(count) { add(readPositionRecord(r)) } }
        }

    fun parseBleRadar(frame: SecureMeshBleFrame.Response): Result<BleRadarState> =
        parseOk(frame, BleOpcode.GET_BLE_RADAR) { bytes ->
            require(bytes.size >= BLE_RADAR_HEADER_SIZE) { "GET_BLE_RADAR too short" }
            val r = Reader(bytes)
            require(r.u8() == 1) { "unsupported BLE radar payload version" }
            val configured = r.u8() != 0
            val scanning = r.u8() != 0
            val count = r.u8()
            val scanCycle = r.u32()
            val totalDetections = r.u32()
            require(r.remaining == count * BLE_RADAR_RECORD_SIZE) { "GET_BLE_RADAR length mismatch" }
            val devices = buildList(count) {
                repeat(count) {
                    val addressHash = r.u32()
                    val ageMs = r.u32()
                    val presenceMs = r.u32()
                    val signalDbm = r.i8()
                    val peakSignalDbm = r.i8()
                    val signalTrendDb = r.i8()
                    val detections = r.u8()
                    val hasName = r.u8() != 0
                    val nameLen = r.u8()
                    require(nameLen <= 12) { "BLE radar name length invalid" }
                    val nameBytes = r.bytes(12)
                    add(
                        NearbyBleDevice(
                            addressHash = addressHash,
                            ageMs = ageMs,
                            presenceMs = presenceMs,
                            signalDbm = signalDbm,
                            peakSignalDbm = peakSignalDbm,
                            signalTrendDb = signalTrendDb,
                            detections = detections,
                            advertisedName = if (hasName && nameLen > 0) nameBytes.copyOf(nameLen).utf8OrReplacement().trimEnd('\u0000') else null,
                        )
                    )
                }
            }
            BleRadarState(configured, scanning, scanCycle, totalDetections, devices)
        }

    fun parseOperationalHealth(frame: SecureMeshBleFrame.Response): Result<OperationalHealth> =
        parseResponse(frame, BleOpcode.GET_OPERATIONAL_HEALTH, 17) { r ->
            require(r.u8() == 1) { "unsupported operational health payload version" }
            OperationalHealth(
                score = r.u8(), level = operationalLevel(r.u8()), flags = r.u16(),
                radioScore = r.u8(), meshScore = r.u8(), routingScore = r.u8(), memoryScore = r.u8(),
                queueScore = r.u8(), gpsScore = r.u8(), bleScore = r.u8(), freshNeighbors = r.u8(),
                routeCount = r.u8(), backupRouteCount = r.u8(), queueUsed = r.u8(), queueCapacity = r.u8(),
            )
        }

    fun parseSelfDiagnostics(frame: SecureMeshBleFrame.Response): Result<DeviceSelfCheck> =
        parseResponse(frame, BleOpcode.GET_SELF_DIAGNOSTICS, 43) { r ->
            require(r.u8() == 1) { "unsupported self-diagnostics payload version" }
            DeviceSelfCheck(
                score = r.u8(), level = operationalLevel(r.u8()), flags = r.u16(),
                radioReady = r.u8() != 0, protectionReady = r.u8() != 0, phoneLinkReady = r.u8() != 0,
                gpsState = r.u8(), displayReady = r.u8() != 0, freshNeighbors = r.u8(), routeCount = r.u8(),
                backupRouteCount = r.u8(), queueUsed = r.u8(), queueCapacity = r.u8(),
                freeHeapBytes = r.u32(), largestHeapBlockBytes = r.u32(), successfulHopAcks = r.u32(),
                hopAckTimeouts = r.u32(), transmitErrors = r.u32(), radioRecoveries = r.u32(), authenticationFailures = r.u32(),
            )
        }

    fun parseEvent(frame: SecureMeshBleFrame.Event): Result<BleDecodedEvent?> = runCatching {
        val type = frame.eventType ?: return@runCatching null
        val r = Reader(frame.payload)
        val decoded: BleDecodedEvent = when (type) {
            BleEventType.NODE_DISCOVERED, BleEventType.NODE_STALE -> { requireSize(frame.payload, 4); BleDecodedEvent.Node(type, nodeId(r.u32())) }
            BleEventType.MESSAGE_QUEUED -> { requireSize(frame.payload, 12); BleDecodedEvent.MessageQueued(r.u32(), nodeId(r.u32()), nodeId(r.u32())) }
            BleEventType.HOP_ACK -> { requireSize(frame.payload, 12); BleDecodedEvent.HopAck(r.u32(), nodeId(r.u32()), r.u32()) }
            BleEventType.RETRY -> { requireSize(frame.payload, 9); BleDecodedEvent.Retry(r.u32(), nodeId(r.u32()), r.u8()) }
            BleEventType.MESSAGE_LOCAL_RECEIVED -> {
                require(frame.payload.size >= 14) { "MESSAGE_LOCAL_RECEIVED too short" }
                val origin = nodeId(r.u32()); val destination = nodeId(r.u32()); val messageId = r.u32(); val messageType = r.u8(); val length = r.u8()
                require(length == r.remaining) { "MESSAGE_LOCAL_RECEIVED payload length mismatch" }
                require(length <= 70) { "MESSAGE_LOCAL_RECEIVED payload too large" }
                BleDecodedEvent.LocalMessage(origin, destination, messageId, messageType, r.bytes(length))
            }
            BleEventType.ROUTE_CHANGED -> { requireSize(frame.payload, 9); BleDecodedEvent.RouteChanged(nodeId(r.u32()), nodeId(r.u32()), r.u8() != 0) }
            BleEventType.TEST_STARTED -> { requireSize(frame.payload, 11); BleDecodedEvent.TestStarted(r.u32(), nodeId(r.u32()), r.u16(), r.u8()) }
            BleEventType.TEST_PACKET_SENT -> { requireSize(frame.payload, 16); BleDecodedEvent.TestPacketSent(r.u32(), r.u32(), nodeId(r.u32()), r.u32()) }
            BleEventType.TEST_PONG_RECEIVED -> { requireSize(frame.payload, 16); BleDecodedEvent.TestPong(r.u32(), r.u32(), r.u32(), r.u32()) }
            BleEventType.TEST_PACKET_TIMEOUT -> { requireSize(frame.payload, 12); BleDecodedEvent.TestTimeout(r.u32(), r.u32(), r.u32()) }
            BleEventType.TEST_PROGRESS -> { requireSize(frame.payload, 16); BleDecodedEvent.TestProgress(r.u32(), r.u32(), r.u32(), r.u32()) }
            BleEventType.TEST_FINISHED -> { requireSize(frame.payload, 18); val testId=r.u32(); val finalState=r.u8(); val reason=r.u8(); BleDecodedEvent.TestFinished(testId, finalState, BleCommandStatus.fromWire(reason), reason, r.u32(), r.u32(), r.u32()) }
            BleEventType.RADIO_RECOVERY -> { requireSize(frame.payload, 6); BleDecodedEvent.RadioRecovery(r.i16(), r.u32()) }
            BleEventType.BLE_STATE -> { requireSize(frame.payload, 1); BleDecodedEvent.BleState(r.u8()) }
            BleEventType.ERROR -> { requireSize(frame.payload, 6); val context=r.u8(); val raw=r.u8(); BleDecodedEvent.Error(context, BleCommandStatus.fromWire(raw), raw, r.u32()) }
            BleEventType.NO_RETURN_ROUTE -> { requireSize(frame.payload, 12); BleDecodedEvent.NoReturnRoute(nodeId(r.u32()), r.u32(), r.u32()) }
            BleEventType.POSITION_UPDATED -> { requireSize(frame.payload, POSITION_RECORD_SIZE); BleDecodedEvent.PositionUpdated(readPositionRecord(r)) }
            BleEventType.SOS_RAISED -> {
                requireSize(frame.payload, 29)
                val origin = nodeId(r.u32())
                require(r.u8() == 1) { "unsupported SOS payload version" }
                val sosType = r.u8(); val flags = r.u8(); r.u8() // reserved
                val sosId = r.u32(); val raised = r.u32(); val lat = r.i32(); val lon = r.i32(); val age = r.u32(); val batteryRaw = r.u8()
                BleDecodedEvent.SosRaised(origin, sosId, sosType, flags, raised, lat, lon, age, batteryRaw.takeUnless { it == 0xFF })
            }
            BleEventType.SOS_ACKNOWLEDGED -> { requireSize(frame.payload, 12); BleDecodedEvent.SosAcknowledged(nodeId(r.u32()), r.u32(), nodeId(r.u32())) }
            BleEventType.OPERATIONAL_HEALTH_CHANGED -> { requireSize(frame.payload, 4); BleDecodedEvent.OperationalHealthChanged(r.u8(), operationalLevel(r.u8()), r.u16()) }
        }
        require(r.remaining == 0) { "event trailing bytes" }
        decoded
    }

    private fun encodePayload(command: SecureMeshBleCommand): ByteArray = when (command) {
        SecureMeshBleCommand.GetInfo, SecureMeshBleCommand.GetStatus, SecureMeshBleCommand.GetNeighbors,
        SecureMeshBleCommand.GetRoutes, SecureMeshBleCommand.StopFieldTest, SecureMeshBleCommand.GetFieldTestStatus,
        SecureMeshBleCommand.PingLocal, SecureMeshBleCommand.ClearStats, SecureMeshBleCommand.GetPositions,
        SecureMeshBleCommand.GetBleRadar, SecureMeshBleCommand.ClearBleRadar, SecureMeshBleCommand.GetOperationalHealth,
        SecureMeshBleCommand.GetSelfDiagnostics -> byteArrayOf()

        is SecureMeshBleCommand.SendMessage -> {
            require(command.bytes.size in 1..70) { "SEND_MESSAGE length must be 1..70" }
            Writer(5 + command.bytes.size).apply { u32(nodeIdValue(command.destination)); u8(command.bytes.size); bytes(command.bytes) }.toByteArray()
        }
        is SecureMeshBleCommand.AddStaticRoute -> Writer(8).apply { u32(nodeIdValue(command.destination)); u32(nodeIdValue(command.nextHop)) }.toByteArray()
        is SecureMeshBleCommand.RemoveStaticRoute -> Writer(4).apply { u32(nodeIdValue(command.destination)) }.toByteArray()
        is SecureMeshBleCommand.StartFieldTest -> {
            require(command.count in 1..500) { "field test count must be 1..500" }
            require(command.intervalMs in 250L..60_000L) { "field test interval must be 250..60000ms" }
            require(command.size in 16..70) { "field test size must be 16..70" }
            Writer(12).apply {
                u32(nodeIdValue(command.target)); u16(command.count); u32(command.intervalMs); u8(command.size); u8(if (command.directOnly) 1 else 0)
            }.toByteArray()
        }
        is SecureMeshBleCommand.RaiseSos -> { require(command.type in 0..0xFF); byteArrayOf(command.type.toByte()) }
        is SecureMeshBleCommand.AckSos -> Writer(8).apply { u32(nodeIdValue(command.origin)); u32(command.sosId) }.toByteArray()
    }

    private fun <T> parseResponse(frame: SecureMeshBleFrame.Response, opcode: BleOpcode, exactLength: Int, block: (Reader) -> T): Result<T> =
        parseOk(frame, opcode) { bytes -> requireSize(bytes, exactLength); val r = Reader(bytes); block(r).also { require(r.remaining == 0) { "response trailing bytes" } } }

    private fun <T> parseOk(frame: SecureMeshBleFrame.Response, opcode: BleOpcode, block: (ByteArray) -> T): Result<T> = runCatching {
        require(frame.opcode == opcode) { "response opcode mismatch: expected $opcode, got ${frame.rawOpcode}" }
        val status = frame.status ?: error("unknown response status ${frame.rawStatus}")
        require(status == BleCommandStatus.OK) { "SecureMesh command failed: $status" }
        block(frame.payload)
    }

    companion object {
        const val MAGIC = 0x4D53
        const val VERSION = 2
        const val HEADER_SIZE = 10
        const val MAX_PACKET_SIZE = 384

        fun nodeId(value: Long): NodeId = value.toString(16).uppercase().padStart(8, '0')

        fun nodeIdValue(value: NodeId): Long {
            val text = value.trim().removePrefix("0x").removePrefix("0X")
            require(text.isNotEmpty() && text.length <= 8) { "Node ID must be a u32 hex value" }
            return text.toLong(16).also { require(it in 0..0xFFFF_FFFFL) }
        }
    }
}

private const val POSITION_RECORD_SIZE = 35
private const val BLE_RADAR_HEADER_SIZE = 12
private const val BLE_RADAR_RECORD_SIZE = 30

private fun operationalLevel(raw: Int): OperationalLevel = when (raw) {
    0 -> OperationalLevel.CRITICAL
    1 -> OperationalLevel.DEGRADED
    2 -> OperationalLevel.GOOD
    3 -> OperationalLevel.EXCELLENT
    else -> error("unknown operational level $raw")
}

private fun readPositionRecord(r: Reader): BlePositionPayload {
    val nodeId = SecureMeshBleProtocolV01Codec.nodeId(r.u32())
    require(r.u8() == 1) { "unsupported position payload version" }
    val flags = r.u8()
    return BlePositionPayload(
        nodeId = nodeId,
        flags = flags,
        sequence = r.u16(),
        gpsEpochSec = r.u32(),
        latitudeE7 = r.i32(),
        longitudeE7 = r.i32(),
        altitudeCm = r.i32(),
        speedCms = r.u16(),
        hdopX100 = r.u16(),
        satellites = r.u8(),
        fixAgeMs = r.u16(),
        receivedAgeMs = r.u32(),
    ).also {
        require(it.latitudeE7 in -900_000_000..900_000_000) { "latitude out of range" }
        require(it.longitudeE7 in -1_800_000_000..1_800_000_000) { "longitude out of range" }
    }
}

private fun requireSize(bytes: ByteArray, exact: Int) { require(bytes.size == exact) { "payload size ${bytes.size}, expected $exact" } }

private class Reader(private val data: ByteArray) {
    private var offset = 0
    val remaining: Int get() = data.size - offset
    fun u8(): Int { require(remaining >= 1) { "truncated u8" }; return data[offset++].toInt() and 0xFF }
    fun i8(): Int { val v=u8(); return if (v and 0x80 != 0) v - 0x100 else v }
    fun u16(): Int { require(remaining >= 2) { "truncated u16" }; val v=(data[offset].toInt() and 0xFF) or ((data[offset+1].toInt() and 0xFF) shl 8); offset += 2; return v }
    fun i16(): Int { val v=u16(); return if (v and 0x8000 != 0) v - 0x10000 else v }
    fun u32(): Long { require(remaining >= 4) { "truncated u32" }; var v=0L; repeat(4) { i -> v = v or ((data[offset+i].toLong() and 0xFF) shl (8*i)) }; offset += 4; return v }
    fun i32(): Int = u32().toInt()
    fun bytes(count: Int): ByteArray { require(count >= 0 && remaining >= count) { "truncated bytes" }; return data.copyOfRange(offset, offset + count).also { offset += count } }
}

private class Writer(capacity: Int) {
    private val data = ByteArray(capacity)
    private var offset = 0
    fun u8(value: Int) { require(value in 0..0xFF && offset + 1 <= data.size); data[offset++] = value.toByte() }
    fun u16(value: Int) { require(value in 0..0xFFFF); u8(value and 0xFF); u8((value ushr 8) and 0xFF) }
    fun u32(value: Long) { require(value in 0..0xFFFF_FFFFL); repeat(4) { u8(((value ushr (8*it)) and 0xFF).toInt()) } }
    fun bytes(value: ByteArray) { require(offset + value.size <= data.size); value.copyInto(data, offset); offset += value.size }
    fun toByteArray(): ByteArray = data.copyOf(offset)
}

fun ByteArray.utf8OrReplacement(): String = toString(StandardCharsets.UTF_8)
