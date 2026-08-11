package dev.securemesh.commander.data.mock

import dev.securemesh.commander.data.transport.MeshTransport
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicLong

class MockTransport(
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : MeshTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simulationJob: Job? = null
    private var testJob: Job? = null
    private var scanJob: Job? = null
    private var scenario = "NORMAL"
    private val idSequence = AtomicLong(0)

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Idle)
    override val connectionState = _connectionState.asStateFlow()
    private val _session = MutableStateFlow<SecureMeshSession?>(null)
    override val session = _session.asStateFlow()
    private val _demoProfile = MutableStateFlow<DemoProfile?>(null)
    override val demoProfile = _demoProfile.asStateFlow()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices = _discoveredDevices.asStateFlow()
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes = _nodes.asStateFlow()
    private val _topology = MutableStateFlow(MeshTopology(emptyList(), emptyList(), 0L))
    override val topology = _topology.asStateFlow()
    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    override val messages = _messages.asStateFlow()
    private val _routes = MutableStateFlow<List<MeshRoute>>(emptyList())
    override val routes = _routes.asStateFlow()
    private val _events = MutableStateFlow<List<MeshEvent>>(emptyList())
    override val events = _events.asStateFlow()
    private val _activeFieldTest = MutableStateFlow<FieldTestSession?>(null)
    override val activeFieldTest = _activeFieldTest.asStateFlow()
    private val _activeSos = MutableStateFlow<SosAlert?>(null)
    override val activeSos = _activeSos.asStateFlow()

    override suspend fun start() {
        if (_nodes.value.isEmpty()) seedNetwork(DemoProfile.CURRENT_FIRMWARE_V05)
    }

    suspend fun launchDemo(profile: DemoProfile) {
        scenario = "NORMAL"
        _demoProfile.value = profile
        seedNetwork(profile)
        val local = requireNotNull(_nodes.value.firstOrNull { it.id == LOCAL_ID })
        val device = DiscoveredDevice(
            address = "DE:A0:00:00:00:01",
            advertisedName = "SecureMesh ${local.name}",
            rssi = -42,
            lastSeenEpochMs = now(),
            classification = DeviceClassification.TRUSTED_SECUREMESH,
            bondStatus = BondStatus.BONDED,
            secureMeshNodeId = local.id,
            protocolVersion = local.protocolVersion,
            deviceType = local.role.name,
            matchReasons = setOf("mock-identity"),
        )
        _connectionState.value = MeshConnectionState.Connected(device, -42, SecureSessionState.ESTABLISHED, true)
        _session.value = buildSession(local.identity, profile)
        addEvent(EventCategory.SYSTEM, "DEMO PROFILE ACTIVE", profile.name)
        startSimulationLoop()
    }

    suspend fun applyScenario(name: String) {
        scenario = name.uppercase()
        addEvent(EventCategory.SYSTEM, "SCENARIO $scenario", "Development scenario applied")
        when (scenario) {
            "NORMAL" -> { setNodeOnline(RELAY_ID, true); resetRoutes(); restoreFutureGps() }
            "WEAK LINK" -> Unit
            "RELAY LOST" -> {
                setNodeOnline(RELAY_ID, false)
                if (_demoProfile.value == DemoProfile.FUTURE_DEMO) {
                    _routes.value = _routes.value.map { route ->
                        if (route.destination == REMOTE_ID) route.copy(type = RouteType.FAILED, updatedAtEpochMs = now()) else route
                    }
                }
            }
            "GPS LOST" -> if (_demoProfile.value == DemoProfile.FUTURE_DEMO) setGpsValid(REMOTE_ID, false)
            "SOS" -> if (_demoProfile.value == DemoProfile.FUTURE_DEMO) raiseSos(REMOTE_ID)
            "MESSAGE RETRY" -> Unit
        }
        rebuildTopology()
    }

    override suspend fun stop() {
        simulationJob?.cancel(); simulationJob = null
        testJob?.cancel(); testJob = null
        scanJob?.cancel(); scanJob = null
        _activeFieldTest.value = null
        _activeSos.value = null
        _session.value = null
        _demoProfile.value = null
        _discoveredDevices.value = emptyList()
        _nodes.value = emptyList()
        _topology.value = MeshTopology(emptyList(), emptyList(), now())
        _messages.value = emptyList()
        _routes.value = emptyList()
        _events.value = emptyList()
        _connectionState.value = MeshConnectionState.Idle
    }

    override suspend fun startScan(durationMs: Long) {
        scanJob?.cancel()
        val boundedDurationMs = durationMs.coerceIn(5_000L, 30_000L)
        val start = now()
        _connectionState.value = MeshConnectionState.Scanning(start, start + boundedDurationMs)
        _discoveredDevices.value = listOf(
            DiscoveredDevice("DE:A0:00:00:00:01", "SecureMesh Field Node", -42, now(), DeviceClassification.KNOWN_SECUREMESH, BondStatus.BONDED, LOCAL_ID, 1, "NODE", setOf("mock-identity")),
            DiscoveredDevice("DE:A0:00:00:00:02", "Nearby Sensor", -79, now(), DeviceClassification.UNKNOWN_BLE, BondStatus.NOT_BONDED),
        )
        _connectionState.value = MeshConnectionState.DeviceFound(_discoveredDevices.value.size, start + boundedDurationMs)
        scanJob = scope.launch {
            delay(boundedDurationMs)
            if (_connectionState.value is MeshConnectionState.DeviceFound || _connectionState.value is MeshConnectionState.Scanning) {
                _connectionState.value = MeshConnectionState.Idle
            }
            scanJob = null
        }
    }

    override suspend fun stopScan() { scanJob?.cancel(); scanJob = null; _connectionState.value = MeshConnectionState.Idle }

    override suspend fun connect(device: DiscoveredDevice) {
        val profile = _demoProfile.value ?: DemoProfile.CURRENT_FIRMWARE_V05
        if (_nodes.value.isEmpty()) seedNetwork(profile)
        _connectionState.value = MeshConnectionState.Connecting(device); delay(120)
        _connectionState.value = MeshConnectionState.DiscoveringServices(device); delay(120)

        if (device.classification == DeviceClassification.UNKNOWN_BLE || device.secureMeshNodeId != LOCAL_ID) {
            _session.value = null
            _connectionState.value = MeshConnectionState.Connected(device, device.rssi, SecureSessionState.NOT_CONFIGURED, false)
            addEvent(EventCategory.SYSTEM, "BLE CONNECTED / SECUREMESH UNKNOWN", device.advertisedName ?: device.address)
            return
        }

        _connectionState.value = MeshConnectionState.IdentifyingSecureMesh(device); delay(100)
        val local = _nodes.value.first { it.id == LOCAL_ID }
        _connectionState.value = MeshConnectionState.Authenticating(device); delay(100)
        _connectionState.value = MeshConnectionState.SyncingSession(local.identity); delay(100)
        _session.value = buildSession(local.identity, profile)
        _connectionState.value = MeshConnectionState.Connected(device, device.rssi, SecureSessionState.ESTABLISHED, true)
        addEvent(EventCategory.SYSTEM, "SECUREMESH SESSION ESTABLISHED", local.id, local.id)
        startSimulationLoop()
    }

    override suspend fun disconnect() {
        _connectionState.value = MeshConnectionState.Disconnecting
        simulationJob?.cancel(); simulationJob = null
        testJob?.cancel(); testJob = null
        scanJob?.cancel(); scanJob = null
        delay(80)
        _session.value = null
        _activeFieldTest.value = _activeFieldTest.value?.copy(running = false, finishedAtEpochMs = now())
        _connectionState.value = MeshConnectionState.Disconnected("User requested")
    }

    override suspend fun sendMessage(destination: NodeId, payload: String): Result<MessageId> {
        val session = _session.value ?: return Result.failure(IllegalStateException("No SecureMesh session"))
        if (!session.can(SessionPermission.SEND_MESSAGE)) return Result.failure(SecurityException("SEND_MESSAGE not granted"))
        val target = _nodes.value.firstOrNull { it.id == destination } ?: return Result.failure(IllegalArgumentException("Unknown node $destination"))
        if (!target.online) return Result.failure(IllegalStateException("Node $destination is offline"))
        val path = resolvePath(destination) ?: return Result.failure(IllegalStateException("Route unavailable"))
        val id = "M-${now()}-${idSequence.incrementAndGet()}"
        upsertMessage(MeshMessage(id, session.localNodeIdentity.nodeId, destination, payload, now(), progressState = MessageDeliveryState.QUEUED))
        addEvent(EventCategory.MESSAGES, "MESSAGE #$id QUEUED", "${session.localNodeIdentity.nodeId} → $destination", destination)

        scope.launch {
            transitionMessage(id, MessageDeliveryState.ROUTING, 120)
            transitionMessage(id, MessageDeliveryState.SENDING, 160)
            var failed = false
            for ((index, pair) in path.zipWithNext().withIndex()) {
                delay(160)
                val link = linkFor(pair.first, pair.second)
                val unavailable = _nodes.value.firstOrNull { it.id == pair.second }?.online == false || link == null
                val retries = if (scenario == "MESSAGE RETRY" && index == 0) 1 else 0
                val hop = TransmissionHop(
                    pair.first, pair.second, "F-${id}-$index",
                    if (unavailable) HopAckState.TIMEOUT else HopAckState.ACKED,
                    retries, link?.rssi, link?.snr, now(),
                )
                updateMessage(id) { old -> old.copy(progressState = MessageDeliveryState.HOP_PROGRESS, hopTrace = old.hopTrace + hop) }
                addEvent(EventCategory.MESSAGES, "${pair.first}→${pair.second} ${hop.ackState}", "frame ${hop.frameId}", destination)
                if (unavailable) { failed = true; break }
            }
            if (failed) {
                updateMessage(id) { it.copy(progressState = MessageDeliveryState.FAILED, finalState = MessageFinalState.FAILED, failureReason = "Hop ACK timeout") }
            } else if (_demoProfile.value == DemoProfile.CURRENT_FIRMWARE_V05) {
                updateMessage(id) { it.copy(progressState = MessageDeliveryState.FINAL_CONFIRMATION_PENDING, finalState = MessageStateMachine.finalStateAfterHopAck()) }
                addEvent(EventCategory.MESSAGES, "MESSAGE #$id E2E UNKNOWN", "All observed hop ACKs succeeded; v0.5 has no end-to-end delivery confirmation", destination)
            } else {
                delay(180)
                updateMessage(id) { it.copy(progressState = MessageDeliveryState.DELIVERED, finalState = MessageFinalState.DELIVERED, deliveredAtEpochMs = now()) }
                addEvent(EventCategory.MESSAGES, "MESSAGE #$id DELIVERED", "Future demo end-to-end confirmation", destination)
            }
        }
        return Result.success(id)
    }

    override suspend fun addStaticRoute(destination: NodeId, via: NodeId): Result<Unit> {
        val session = _session.value ?: return Result.failure(IllegalStateException("No session"))
        if (!session.can(SessionPermission.MANAGE_ROUTES)) return Result.failure(SecurityException("MANAGE_ROUTES not granted"))
        if (_nodes.value.none { it.id == destination } || _nodes.value.none { it.id == via }) return Result.failure(IllegalArgumentException("Unknown node"))
        _routes.value = _routes.value.filterNot { it.destination == destination } + MeshRoute(destination, via, RouteType.STATIC)
        addEvent(EventCategory.ROUTING, "STATIC ROUTE UPDATED", "$destination via $via", destination)
        return Result.success(Unit)
    }

    override suspend fun removeRoute(destination: NodeId): Result<Unit> {
        val session = _session.value ?: return Result.failure(IllegalStateException("No session"))
        if (!session.can(SessionPermission.MANAGE_ROUTES)) return Result.failure(SecurityException("MANAGE_ROUTES not granted"))
        _routes.value = _routes.value.filterNot { it.destination == destination && it.type == RouteType.STATIC }
        addEvent(EventCategory.ROUTING, "STATIC ROUTE REMOVED", destination, destination)
        return Result.success(Unit)
    }

    override suspend fun startFieldTest(config: FieldTestConfig): Result<String> {
        val session = _session.value ?: return Result.failure(IllegalStateException("No session"))
        if (!session.can(SessionPermission.RUN_FIELD_TEST)) return Result.failure(SecurityException("RUN_FIELD_TEST not granted"))
        if (config.source != session.localNodeIdentity.nodeId) return Result.failure(IllegalArgumentException("Field test source must be local node"))
        if (_activeFieldTest.value?.running == true) return Result.failure(IllegalStateException("Field test already running"))
        val path = if (config.mode == FieldTestMode.DIRECT) listOf(config.source, config.target) else resolvePath(config.target) ?: return Result.failure(IllegalStateException("Route unavailable"))
        val id = "T${now()}"
        val e2eAvailable = _demoProfile.value == DemoProfile.FUTURE_DEMO
        _activeFieldTest.value = FieldTestSession(id, config, now(), confirmedReceived = if (e2eAvailable) 0 else null, confirmedLost = if (e2eAvailable) 0 else null, route = path)
        testJob = scope.launch {
            repeat(config.packetCount) { index ->
                ensureActive()
                val hops = path.zipWithNext().map { (from, to) ->
                    val base = linkFor(from, to)
                    val weak = scenario == "WEAK LINK" && from == RELAY_ID && to == REMOTE_ID
                    val rssi = base?.rssi?.let { it + (if (weak) -18 else 0) + random.nextInt(-3, 4) }
                    val snr = base?.snr?.let { it + (if (weak) -6.0 else 0.0) + random.nextDouble(-0.8, 0.8) }
                    val ack = if (base == null || (_nodes.value.firstOrNull { it.id == to }?.online == false)) HopAckState.TIMEOUT else if (weak && random.nextDouble() < .18) HopAckState.TIMEOUT else HopAckState.ACKED
                    HopTestTelemetry(from, to, ack, if (ack == HopAckState.ACKED && random.nextDouble() < .1) 1 else if (ack == HopAckState.TIMEOUT) 2 else 0, rssi, snr)
                }
                val hopFailed = hops.any { it.ackState != HopAckState.ACKED }
                val final = if (!e2eAvailable) FieldPacketFinalResult.UNKNOWN else if (hopFailed || random.nextDouble() < .03) FieldPacketFinalResult.FAILED else FieldPacketFinalResult.CONFIRMED_RECEIVED
                val point = TelemetryPoint(index + 1, now(), final, hops)
                val old = _activeFieldTest.value ?: return@launch
                _activeFieldTest.value = old.copy(
                    sent = old.sent + 1,
                    confirmedReceived = old.confirmedReceived?.plus(if (final == FieldPacketFinalResult.CONFIRMED_RECEIVED) 1 else 0),
                    confirmedLost = old.confirmedLost?.plus(if (final == FieldPacketFinalResult.FAILED) 1 else 0),
                    retries = old.retries + point.retryCount(),
                    points = (old.points + point).takeLast(240),
                )
                delay(config.intervalMs.coerceAtLeast(50))
            }
            _activeFieldTest.value = _activeFieldTest.value?.copy(running = false, finishedAtEpochMs = now())
            val pdr = _activeFieldTest.value?.pdr
            addEvent(EventCategory.RADIO, "FIELD TEST COMPLETE", pdr?.let { "E2E PDR ${"%.1f".format(it * 100)}%" } ?: "E2E PDR unavailable; hop telemetry captured")
        }
        return Result.success(id)
    }

    override suspend fun stopFieldTest() { testJob?.cancel(); _activeFieldTest.value = _activeFieldTest.value?.copy(running = false, finishedAtEpochMs = now()) }

    override suspend fun acknowledgeSos(id: String) {
        val session = _session.value ?: return
        if (!session.can(SessionPermission.ACKNOWLEDGE_SOS)) return
        _activeSos.value?.takeIf { it.id == id }?.let { _activeSos.value = it.copy(acknowledged = true); delay(150); _activeSos.value = null }
    }

    private fun buildSession(identity: NodeIdentity, profile: DemoProfile): SecureMeshSession {
        val permissions = if (profile == DemoProfile.CURRENT_FIRMWARE_V05) setOf(
            SessionPermission.VIEW_MESSAGES, SessionPermission.SEND_MESSAGE, SessionPermission.VIEW_OWN_NODE,
            SessionPermission.VIEW_NODES, SessionPermission.VIEW_NETWORK_TOPOLOGY, SessionPermission.VIEW_ROUTES,
            SessionPermission.RUN_FIELD_TEST, SessionPermission.VIEW_NETWORK_DIAGNOSTICS, SessionPermission.MANAGE_ROUTES,
        ) else SessionPermission.entries.toSet()
        return SecureMeshSession(identity, SecureSessionConnectionState.SECURE_SESSION_ESTABLISHED, AuthenticationState.AUTHENTICATED, permissions, now())
    }

    private fun seedNetwork(profile: DemoProfile) {
        val t = now()
        fun identity(id: String, name: String, role: NodeRole, caps: Set<DeviceCapability>) = NodeIdentity(id, name, role, if (profile == DemoProfile.CURRENT_FIRMWARE_V05) "0.5" else "0.9-demo", 1, caps)
        val baseCaps = setOf(DeviceCapability.MESSAGING, DeviceCapability.FIELD_TEST, DeviceCapability.NETWORK_DIAGNOSTICS)
        val futureCaps = if (profile == DemoProfile.FUTURE_DEMO) setOf(DeviceCapability.GPS, DeviceCapability.SOS, DeviceCapability.ROUTING) else setOf(DeviceCapability.ROUTING)
        fun pos(id: String, lat: Double, lon: Double) = if (profile == DemoProfile.FUTURE_DEMO) NodePosition(id, lat, lon, t, 8, 1.1, .2, true) else null
        fun telemetry(uptime: Long, battery: Int, voltage: Double): Triple<Long?, Int?, Double?> =
            if (profile == DemoProfile.FUTURE_DEMO) Triple(uptime, battery, voltage) else Triple(null, null, null)
        val localTelemetry = telemetry(14_200, 91, 4.05)
        val relayTelemetry = telemetry(11_440, 84, 3.98)
        val remoteTelemetry = telemetry(9_230, 76, 3.87)
        val directTelemetry = telemetry(7_650, 62, 3.78)
        _nodes.value = listOf(
            MeshNode(identity(LOCAL_ID, if (profile == DemoProfile.CURRENT_FIRMWARE_V05) "Field Node" else "Command Node", if (profile == DemoProfile.CURRENT_FIRMWARE_V05) NodeRole.MEMBER else NodeRole.COMMANDER, baseCaps + futureCaps), true, t, localTelemetry.first, localTelemetry.second, localTelemetry.third, pos(LOCAL_ID,53.6840,25.1360)),
            MeshNode(identity(RELAY_ID, "Relay North", NodeRole.RELAY, setOf(DeviceCapability.MESSAGING, DeviceCapability.RELAY, DeviceCapability.ROUTING) + if(profile==DemoProfile.FUTURE_DEMO)setOf(DeviceCapability.GPS) else emptySet()), true, t, relayTelemetry.first, relayTelemetry.second, relayTelemetry.third, pos(RELAY_ID,53.6852,25.1390)),
            MeshNode(identity(REMOTE_ID, "Team Node", NodeRole.MEMBER, setOf(DeviceCapability.MESSAGING) + if(profile==DemoProfile.FUTURE_DEMO)setOf(DeviceCapability.GPS,DeviceCapability.SOS) else emptySet()), true, t, remoteTelemetry.first, remoteTelemetry.second, remoteTelemetry.third, pos(REMOTE_ID,53.6864,25.1422)),
            MeshNode(identity(DIRECT_ID, "Direct Node", NodeRole.MEMBER, setOf(DeviceCapability.MESSAGING) + if(profile==DemoProfile.FUTURE_DEMO)setOf(DeviceCapability.GPS) else emptySet()), true, t, directTelemetry.first, directTelemetry.second, directTelemetry.third, pos(DIRECT_ID,53.6823,25.1382)),
        )
        resetRoutes(emit = false)
        rebuildTopology()
        _messages.value = emptyList(); _activeSos.value = null; _activeFieldTest.value = null
    }

    private fun resetRoutes(emit: Boolean = true) {
        val future = _demoProfile.value == DemoProfile.FUTURE_DEMO
        val timestamp = now()
        _routes.value = listOf(
            MeshRoute(RELAY_ID, RELAY_ID, RouteType.DIRECT, hopCount = if (future) 1 else null, quality = if (future) .99 else null, updatedAtEpochMs = if (future) timestamp else null),
            MeshRoute(REMOTE_ID, RELAY_ID, if (future) RouteType.DYNAMIC else RouteType.STATIC, hopCount = if (future) 2 else null, quality = if (future) .91 else null, updatedAtEpochMs = if (future) timestamp else null),
            MeshRoute(DIRECT_ID, DIRECT_ID, RouteType.DIRECT, hopCount = if (future) 1 else null, quality = if (future) .98 else null, updatedAtEpochMs = if (future) timestamp else null),
        )
        if (emit) addEvent(
            EventCategory.ROUTING,
            "ROUTES RESTORED",
            if (future) "Future demo route table includes dynamic routing" else "DIRECT/STATIC v0.5-compatible route table",
        )
    }

    private fun resolvePath(destination: NodeId): List<NodeId>? {
        val local = _session.value?.localNodeIdentity?.nodeId ?: LOCAL_ID
        if (destination == local) return listOf(local)
        val route = _routes.value.firstOrNull { it.destination == destination && it.type != RouteType.FAILED } ?: return null
        return if (route.nextHop == destination) listOf(local, destination) else listOf(local, route.nextHop, destination)
    }

    private fun linkFor(from: NodeId, to: NodeId): MeshLink? = _topology.value.links.firstOrNull { it.fromNode == from && it.toNode == to }

    private fun rebuildTopology() {
        val t = now()
        val future = _demoProfile.value == DemoProfile.FUTURE_DEMO
        fun link(from: String, to: String, rssi: Int, snr: Double, pdr: Double, retries: Int) =
            MeshLink(from, to, rssi, snr, if (future) pdr else null, if (future) retries else null, t)
        val relayOnline = _nodes.value.firstOrNull { it.id == RELAY_ID }?.online == true
        val remoteOnline = _nodes.value.firstOrNull { it.id == REMOTE_ID }?.online == true
        val directOnline = _nodes.value.firstOrNull { it.id == DIRECT_ID }?.online == true
        val weak = scenario == "WEAK LINK"
        val links = buildList {
            if (relayOnline) { add(link(LOCAL_ID, RELAY_ID, -72, 7.5, .97, 1)); add(link(RELAY_ID, LOCAL_ID, -65, 9.1, .99, 0)) }
            if (relayOnline && remoteOnline) { add(link(RELAY_ID, REMOTE_ID, if(weak)-101 else -81, if(weak)-3.0 else 4.1, if(weak).78 else .93, if(weak)3 else 2)); add(link(REMOTE_ID, RELAY_ID, -76, 5.2, .95, 1)) }
            if (directOnline) { add(link(LOCAL_ID, DIRECT_ID, -67, 9.0, .98, 0)); add(link(DIRECT_ID, LOCAL_ID, -71, 7.8, .97, 1)) }
        }
        _topology.value = MeshTopology(_nodes.value.map { it.id }, links, t)
    }

    private fun startSimulationLoop() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            while (isActive) {
                delay(1000); val t = now()
                _nodes.value = _nodes.value.map { n -> if (!n.online) n else n.copy(lastSeenEpochMs=t, uptimeSec=n.uptimeSec?.plus(1), batteryPercent=n.batteryPercent?.let{ if(random.nextInt(100)==0)(it-1).coerceAtLeast(0) else it }, position=n.position?.let { p -> if(scenario=="GPS LOST"&&n.id==REMOTE_ID)p.copy(valid=false) else p.copy(latitude=p.latitude+random.nextDouble(-.00002,.00002),longitude=p.longitude+random.nextDouble(-.00002,.00002),timestampEpochMs=t) }) }
                if (scenario == "RELAY LOST") setNodeOnline(RELAY_ID, false, emitEvent = false)
                rebuildTopology()
            }
        }
    }

    private fun restoreFutureGps() { if (_demoProfile.value == DemoProfile.FUTURE_DEMO) setGpsValid(REMOTE_ID, true, emitEvent = false) }
    private fun setNodeOnline(id: String, online: Boolean, emitEvent: Boolean = true) {
        val timestamp = now()
        _nodes.value = _nodes.value.map { node ->
            if (node.id != id) node else {
                val lastSeen = when {
                    online -> timestamp
                    node.online -> timestamp // mark the moment the node was last observed before transition offline
                    else -> node.lastSeenEpochMs // do not refresh an already-offline node
                }
                node.copy(online = online, lastSeenEpochMs = lastSeen)
            }
        }
        if (emitEvent) addEvent(EventCategory.SYSTEM, "NODE ${if (online) "ONLINE" else "OFFLINE"}", id, id)
    }
    private fun setGpsValid(id: String, valid: Boolean, emitEvent: Boolean = true) { _nodes.value = _nodes.value.map { n -> if(n.id==id) n.copy(position=n.position?.copy(valid=valid,timestampEpochMs=now())) else n }; if(emitEvent)addEvent(EventCategory.GPS,"GPS ${if(valid)"FIX" else "LOST"}",id,id) }
    private fun raiseSos(id: String) { val n=_nodes.value.firstOrNull{it.id==id}?:return; _activeSos.value=SosAlert("SOS${now()}",id,now(),n.position,n.batteryPercent,"Mesh online"); addEvent(EventCategory.SOS,"SOS RECEIVED",id,id) }
    private suspend fun transitionMessage(id:String,state:MessageDeliveryState,delayMs:Long){delay(delayMs);updateMessage(id){old->if(MessageStateMachine.canTransition(old.progressState,state))old.copy(progressState=state)else old}}
    private fun upsertMessage(m:MeshMessage){_messages.value=(_messages.value.filterNot{it.id==m.id}+m).sortedByDescending{it.createdAtEpochMs}}
    private fun updateMessage(id:String,transform:(MeshMessage)->MeshMessage){_messages.value=_messages.value.map{if(it.id==id)transform(it)else it}}
    private fun addEvent(category:EventCategory,title:String,details:String,nodeId:String?=null){val timestamp=now();val e=MeshEvent("E-$timestamp-${idSequence.incrementAndGet()}",timestamp,category,title,details,nodeId);_events.value=(listOf(e)+_events.value).take(500)}

    private companion object {
        const val LOCAL_ID = "SM-7C21"
        const val RELAY_ID = "SM-19AF"
        const val REMOTE_ID = "SM-B442"
        const val DIRECT_ID = "SM-D910"
    }
}
