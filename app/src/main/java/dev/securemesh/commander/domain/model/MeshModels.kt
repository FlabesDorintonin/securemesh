package dev.securemesh.commander.domain.model

typealias NodeId = String
typealias MessageId = String

enum class TransportMode { MOCK, BLE }
enum class DemoProfile { CURRENT_FIRMWARE_V05, FUTURE_DEMO }

enum class DeviceClassification {
    UNKNOWN_BLE,
    SECUREMESH_CANDIDATE,
    KNOWN_SECUREMESH,
    TRUSTED_SECUREMESH,
}

enum class BondStatus { BONDED, BONDING, NOT_BONDED, UNKNOWN }

data class DiscoveredDevice(
    val address: String,
    val advertisedName: String?,
    val rssi: Int,
    val lastSeenEpochMs: Long,
    val classification: DeviceClassification,
    val bondStatus: BondStatus,
    /** SecureMesh identity is populated only from authenticated INFO, never inferred from BLE MAC. */
    val secureMeshNodeId: NodeId? = null,
    val protocolVersion: Int? = null,
    val deviceType: String? = null,
    val matchReasons: Set<String> = emptySet(),
)

enum class SecureSessionState { NOT_CONFIGURED, NOT_AUTHENTICATED, AUTHENTICATING, ESTABLISHED }
enum class AuthenticationState { NOT_AUTHENTICATED, PAIRING_REQUIRED, AUTHENTICATING, AUTHENTICATED, FAILED }
enum class SecureSessionConnectionState { BLE_CONNECTED, SECURE_SESSION_ESTABLISHED }

sealed interface MeshConnectionState {
    data object BluetoothUnavailable : MeshConnectionState
    data object BluetoothDisabled : MeshConnectionState
    data class PermissionRequired(val permissions: List<String>) : MeshConnectionState
    data object Idle : MeshConnectionState
    data class Scanning(val startedAtEpochMs: Long, val endsAtEpochMs: Long) : MeshConnectionState
    data class DeviceFound(val count: Int, val scanEndsAtEpochMs: Long) : MeshConnectionState
    data class Connecting(val device: DiscoveredDevice) : MeshConnectionState
    data class PairingRequired(val device: DiscoveredDevice, val expiresAtEpochMs: Long) : MeshConnectionState
    data class Authenticating(val device: DiscoveredDevice) : MeshConnectionState
    data class DiscoveringServices(val device: DiscoveredDevice) : MeshConnectionState
    data class IdentifyingSecureMesh(val device: DiscoveredDevice) : MeshConnectionState
    data class SyncingSession(val identity: NodeIdentity) : MeshConnectionState
    data class Connected(
        val device: DiscoveredDevice,
        /** BLE link RSSI only. This is not a MeshNode radio metric. */
        val linkRssi: Int? = null,
        val secureSession: SecureSessionState = SecureSessionState.NOT_CONFIGURED,
        val protocolConfigured: Boolean = false,
    ) : MeshConnectionState
    data class Reconnecting(val identityHint: String, val attempt: Int, val nextDelayMs: Long) : MeshConnectionState
    data object Disconnecting : MeshConnectionState
    data class Disconnected(val reason: String? = null) : MeshConnectionState
    data class Error(val error: MeshError) : MeshConnectionState
}

enum class MeshErrorCode {
    BLUETOOTH_UNAVAILABLE, PERMISSION_DENIED, SCAN_FAILED, CONNECTION_TIMEOUT,
    CONNECTION_LOST, PAIRING_FAILED, PROTOCOL_MISMATCH, COMMAND_TIMEOUT,
    ROUTE_UNAVAILABLE, NODE_OFFLINE, AUTHORIZATION_REQUIRED, UNKNOWN
}

data class MeshError(
    val code: MeshErrorCode,
    val technicalMessage: String,
    val userMessage: String,
    val recoverable: Boolean = true,
)

enum class NodeRole { MEMBER, RELAY, TEAM_LEADER, OPERATOR, COMMANDER, ADMIN, DEVELOPMENT, UNKNOWN }

enum class DeviceCapability {
    MESSAGING,
    GPS,
    RELAY,
    SOS,
    FIELD_TEST,
    ROUTING,
    STATIC_ROUTING,
    BLE_CONTROL,
    NETWORK_DIAGNOSTICS,
    OTA,
    SENSORS,
}

enum class SessionPermission {
    VIEW_MESSAGES,
    SEND_MESSAGE,
    VIEW_OWN_NODE,
    VIEW_NODES,
    VIEW_OWN_POSITION,
    VIEW_TEAM_POSITIONS,
    VIEW_NETWORK_TOPOLOGY,
    VIEW_ROUTES,
    RUN_FIELD_TEST,
    VIEW_SYSTEM_LOG,
    VIEW_NETWORK_DIAGNOSTICS,
    MANAGE_ROUTES,
    MANAGE_NODES,
    MANAGE_NETWORK,
    VIEW_SOS,
    ACKNOWLEDGE_SOS,
}

data class NodeIdentity(
    val nodeId: NodeId,
    val displayName: String,
    val role: NodeRole,
    val firmwareVersion: String?,
    val protocolVersion: Int?,
    val capabilities: Set<DeviceCapability>,
)

/**
 * Authenticated SecureMesh context for the ESP32 node directly attached to this phone.
 * UI visibility is convenience only, never authorization; firmware validates privileged commands.
 */
data class SecureMeshSession(
    val localNodeIdentity: NodeIdentity,
    val connectionState: SecureSessionConnectionState,
    val authenticationState: AuthenticationState,
    val grantedPermissions: Set<SessionPermission>,
    val connectedSinceEpochMs: Long,
) {
    val capabilities: Set<DeviceCapability> get() = localNodeIdentity.capabilities
    val protocolVersion: Int? get() = localNodeIdentity.protocolVersion
    val firmwareVersion: String? get() = localNodeIdentity.firmwareVersion
    fun can(permission: SessionPermission): Boolean =
        authenticationState == AuthenticationState.AUTHENTICATED && permission in grantedPermissions
    fun supports(capability: DeviceCapability): Boolean = capability in capabilities
}

enum class GpsStatus { FIX, STALE, NO_FIX, INVALID, UNKNOWN }

data class NodePosition(
    val nodeId: NodeId,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMs: Long,
    val satellites: Int?,
    val hdop: Double?,
    val speedMps: Double?,
    val valid: Boolean,
) {
    fun ageMs(nowEpochMs: Long): Long = (nowEpochMs - timestampEpochMs).coerceAtLeast(0)
    fun status(nowEpochMs: Long): GpsStatus = when {
        !valid -> GpsStatus.INVALID
        satellites == null -> GpsStatus.UNKNOWN
        satellites <= 0 -> GpsStatus.NO_FIX
        ageMs(nowEpochMs) > 15_000 -> GpsStatus.STALE
        else -> GpsStatus.FIX
    }
}

data class MeshNode(
    val identity: NodeIdentity,
    val online: Boolean,
    val lastSeenEpochMs: Long,
    val uptimeSec: Long? = null,
    val batteryPercent: Int? = null,
    val voltage: Double? = null,
    val position: NodePosition? = null,
) {
    val id: NodeId get() = identity.nodeId
    val name: String get() = identity.displayName
    val role: NodeRole get() = identity.role
    val firmwareVersion: String? get() = identity.firmwareVersion
    val protocolVersion: Int? get() = identity.protocolVersion
    val capabilities: Set<DeviceCapability> get() = identity.capabilities
    fun supports(capability: DeviceCapability): Boolean = capability in capabilities
}

enum class LinkQuality { EXCELLENT, GOOD, DEGRADED, CRITICAL, UNKNOWN }

data class MeshLink(
    val fromNode: NodeId,
    val toNode: NodeId,
    val rssi: Int? = null,
    val snr: Double? = null,
    val pdr: Double? = null,
    val retries: Int? = null,
    val lastSeenEpochMs: Long? = null,
) {
    fun quality(): LinkQuality = when {
        rssi == null -> LinkQuality.UNKNOWN
        rssi >= -70 -> LinkQuality.EXCELLENT
        rssi >= -85 -> LinkQuality.GOOD
        rssi >= -100 -> LinkQuality.DEGRADED
        else -> LinkQuality.CRITICAL
    }
}

enum class RouteType { DIRECT, STATIC, DYNAMIC, STALE, FAILED }

data class MeshRoute(
    val destination: NodeId,
    val nextHop: NodeId,
    val type: RouteType,
    val hopCount: Int? = null,
    val quality: Double? = null,
    val updatedAtEpochMs: Long? = null,
    val path: List<NodeId>? = null,
)

data class MeshTopology(
    val nodes: List<NodeId>,
    /** Directional links: A→B and B→A are independent observations. */
    val links: List<MeshLink>,
    val updatedAtEpochMs: Long,
)

enum class MessagePriority { NORMAL, HIGH, SYSTEM, EMERGENCY }
enum class ConversationType { DIRECT, GROUP, TEAM, SYSTEM, COMMAND }
enum class HopAckState { PENDING, ACKED, NACKED, TIMEOUT, UNAVAILABLE }

data class TransmissionHop(
    val from: NodeId,
    val to: NodeId,
    val frameId: String?,
    val ackState: HopAckState,
    val retries: Int?,
    val rssi: Int?,
    val snr: Double?,
    val timestampEpochMs: Long,
)

enum class MessageDeliveryState {
    QUEUED, ROUTING, SENDING, HOP_PROGRESS, FINAL_CONFIRMATION_PENDING, DELIVERED, FAILED, EXPIRED,
}

enum class MessageFinalState { PENDING, DELIVERED, FAILED, EXPIRED, UNKNOWN }

data class MeshMessage(
    val id: MessageId,
    val origin: NodeId,
    val destination: NodeId,
    val payload: String,
    val createdAtEpochMs: Long,
    val priority: MessagePriority = MessagePriority.NORMAL,
    val progressState: MessageDeliveryState,
    val finalState: MessageFinalState = MessageFinalState.PENDING,
    val hopTrace: List<TransmissionHop> = emptyList(),
    val conversationType: ConversationType = ConversationType.DIRECT,
    val conversationId: String? = null,
    val deliveredAtEpochMs: Long? = null,
    val failureReason: String? = null,
) {
    fun observedRoute(): List<NodeId> {
        if (hopTrace.isEmpty()) return listOf(origin, destination).distinct()
        return buildList { add(hopTrace.first().from); hopTrace.forEach { add(it.to) } }
    }
    fun totalRetries(): Int? = hopTrace.mapNotNull { it.retries }.takeIf { it.isNotEmpty() }?.sum()
    fun deliveryTimeMs(): Long? = deliveredAtEpochMs?.minus(createdAtEpochMs)
}

data class ConversationDescriptor(
    val id: String,
    val type: ConversationType,
    val title: String,
    val participants: Set<NodeId>,
    val canSend: Boolean,
)

enum class EventCategory { RADIO, ROUTING, MESSAGES, GPS, SECURITY, SYSTEM, SOS }

data class MeshEvent(
    val id: String,
    val timestampEpochMs: Long,
    val category: EventCategory,
    val title: String,
    val details: String,
    val nodeId: NodeId? = null,
)

enum class FieldTestMode { DIRECT, ROUTED, AUTO }

data class FieldTestConfig(
    val source: NodeId,
    val target: NodeId,
    val mode: FieldTestMode,
    val packetCount: Int,
    val intervalMs: Long,
    val payloadBytes: Int,
)

enum class FieldPacketFinalResult { CONFIRMED_RECEIVED, FAILED, UNKNOWN }

data class HopTestTelemetry(
    val from: NodeId,
    val to: NodeId,
    val ackState: HopAckState,
    val retries: Int?,
    val rssi: Int?,
    val snr: Double?,
)

data class TelemetryPoint(
    val packetIndex: Int,
    val timestampEpochMs: Long,
    val finalResult: FieldPacketFinalResult,
    val hopResults: List<HopTestTelemetry>,
) {
    fun rssiSamples(): List<Int> = hopResults.mapNotNull { it.rssi }
    fun snrSamples(): List<Double> = hopResults.mapNotNull { it.snr }
    fun retryCount(): Int = hopResults.mapNotNull { it.retries }.sum()
}

data class FieldTestSession(
    val id: String,
    val config: FieldTestConfig,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val sent: Int = 0,
    val confirmedReceived: Int? = null,
    val confirmedLost: Int? = null,
    val retries: Int = 0,
    val route: List<NodeId> = emptyList(),
    val points: List<TelemetryPoint> = emptyList(),
    val running: Boolean = true,
    val firstHopAcked: Int? = null,
    val firstHopFailures: Int? = null,
    val rttAverageMs: Long? = null,
    val rttMinimumMs: Long? = null,
    val rttMaximumMs: Long? = null,
    val currentNextHop: NodeId? = null,
    val averageFirstHopRssiDbm: Double? = null,
    val averageFirstHopSnrDb: Double? = null,
) {
    val pdr: Double? get() = if (sent == 0 || confirmedReceived == null) null else confirmedReceived.toDouble() / sent.toDouble()
    private fun rssiValues() = points.flatMap { it.rssiSamples() }
    private fun snrValues() = points.flatMap { it.snrSamples() }
    fun averageRssi(): Double? = averageFirstHopRssiDbm ?: rssiValues().takeIf { it.isNotEmpty() }?.average()
    fun minRssi(): Int? = rssiValues().minOrNull()
    fun maxRssi(): Int? = rssiValues().maxOrNull()
    fun averageSnr(): Double? = averageFirstHopSnrDb ?: snrValues().takeIf { it.isNotEmpty() }?.average()
    fun minSnr(): Double? = snrValues().minOrNull()
    fun maxSnr(): Double? = snrValues().maxOrNull()
}

data class SosAlert(
    val id: String,
    val nodeId: NodeId,
    val raisedAtEpochMs: Long,
    val position: NodePosition?,
    val batteryPercent: Int?,
    val networkStatus: String,
    val acknowledged: Boolean = false,
)

data class ProtocolInfo(
    val protocolVersion: Int?,
    val firmwareVersion: String?,
    val capabilities: Set<DeviceCapability>,
)

data class AppSettings(
    val theme: String = "DARK",
    val units: String = "METRIC",
    val keepScreenAwakeDuringTest: Boolean = true,
    val autoReconnect: Boolean = true,
    val scanDurationSec: Int = 12,
    val showUnknownBle: Boolean = true,
    val rememberTrustedNode: Boolean = true,
    val secureScreen: Boolean = true,
    val positionHistory: Boolean = true,
    val storeEvents: Boolean = true,
    val retentionDays: Int = 30,
    val mockMode: Boolean = false,
    val rawBle: Boolean = false,
    val verboseLogs: Boolean = false,
    val simulateFailures: Boolean = false,
    val developerMode: Boolean = false,
)

data class TrustedDeviceMetadata(
    val nodeId: NodeId,
    val displayName: String?,
    val trustedAtEpochMs: Long,
    val protocolVersion: Int?,
    val lastSeenBleAddress: String? = null,
    val firmwareVersion: String? = null,
)

data class BleDiagnostics(
    val nodeId: NodeId? = null,
    val bleAddress: String? = null,
    val gattState: String = "IDLE",
    val bonded: Boolean? = null,
    val protocolVersion: Int? = null,
    val firmwareVersion: String? = null,
    val mtu: Int = 23,
    val responseSubscribed: Boolean = false,
    val eventSubscribed: Boolean = false,
    val secureSessionState: SecureSessionState = SecureSessionState.NOT_CONFIGURED,
    val lastCommandRequestId: Int? = null,
    val lastResponse: String? = null,
    val reassemblyErrors: Int = 0,
    val malformedPacketCount: Int = 0,
)
