package dev.securemesh.commander.data.repository

import dev.securemesh.commander.core.database.*
import dev.securemesh.commander.core.settings.SettingsDataSource
import dev.securemesh.commander.data.mock.MockTransport
import dev.securemesh.commander.data.transport.MeshTransport
import dev.securemesh.commander.data.transport.TransportRouter
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SecureMeshRepositoryImpl(
    private val router: TransportRouter,
    private val mockTransport: MockTransport,
    private val dao: SecureMeshDao,
    private val settingsStore: SettingsDataSource,
    private val now: () -> Long = System::currentTimeMillis,
) : SecureMeshRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconnectOverride = MutableStateFlow<MeshConnectionState?>(null)
    private var reconnectJob: Job? = null

    override val transportMode: StateFlow<TransportMode> = router.mode
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> activeFlow(selector: (MeshTransport) -> StateFlow<T>): Flow<T> =
        router.mode.flatMapLatest { mode -> selector(if (mode == TransportMode.MOCK) router.mock else router.ble) }

    override val connectionState: StateFlow<MeshConnectionState> = combine(activeFlow { it.connectionState }, reconnectOverride) { transportState, override -> override ?: transportState }
        .stateIn(scope, SharingStarted.Eagerly, MeshConnectionState.Idle)
    override val session = activeFlow { it.session }.stateIn(scope, SharingStarted.Eagerly, null)
    override val demoProfile = activeFlow { it.demoProfile }.stateIn(scope, SharingStarted.Eagerly, null)
    override val discoveredDevices = activeFlow { it.discoveredDevices }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val nodes = activeFlow { it.nodes }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val topology = activeFlow { it.topology }.stateIn(scope, SharingStarted.Eagerly, MeshTopology(emptyList(), emptyList(), 0L))
    override val messages = activeFlow { it.messages }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val routes = activeFlow { it.routes }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val activeFieldTest = activeFlow { it.activeFieldTest }.stateIn(scope, SharingStarted.Eagerly, null)
    override val activeSos = activeFlow { it.activeSos }.stateIn(scope, SharingStarted.Eagerly, null)
    override val bleDiagnostics = activeFlow { it.bleDiagnostics }.stateIn(scope, SharingStarted.Eagerly, null)
    override val settings = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    private val localHistoryOwnerNodeId = settingsStore.localHistoryOwnerNodeId.stateIn(scope, SharingStarted.Eagerly, null)
    private val liveEvents = activeFlow { it.events }

    init {
        scope.launch { router.current().start() }
        scope.launch {
            combine(liveEvents, session, localHistoryOwnerNodeId) { events, currentSession, owner -> Triple(events, currentSession, owner) }
                .collect { (events, currentSession, owner) ->
                    if (historyOwnedByCurrentSession(currentSession, owner) && settings.value.storeEvents && events.isNotEmpty()) {
                        dao.upsertEvents(events.map { it.toEntity() })
                        dao.deleteEventsBefore(now() - settings.value.retentionDays * 86_400_000L)
                    }
                }
        }
        scope.launch {
            combine(messages, session, localHistoryOwnerNodeId) { items, currentSession, owner -> Triple(items, currentSession, owner) }
                .collect { (items, currentSession, owner) ->
                    if (historyOwnedByCurrentSession(currentSession, owner) && items.isNotEmpty()) {
                        dao.upsertMessages(items.map { message -> message.toEntity() })
                    }
                }
        }
        scope.launch {
            combine(nodes, session, localHistoryOwnerNodeId) { list, currentSession, owner -> Triple(list, currentSession, owner) }
                .collect { (list, currentSession, owner) ->
                    if (!historyOwnedByCurrentSession(currentSession, owner)) return@collect
                    if (list.isNotEmpty()) dao.upsertKnownNodes(list.map { node -> node.toEntity() })
                    if (settings.value.positionHistory) {
                        val positions = list.mapNotNull { it.position }.map { position -> position.toEntity() }
                        if (positions.isNotEmpty()) {
                            dao.upsertPositions(positions)
                            dao.deletePositionsBefore(now() - settings.value.retentionDays * 86_400_000L)
                        }
                    }
                }
        }
        scope.launch {
            combine(activeFieldTest, session, localHistoryOwnerNodeId) { test, currentSession, owner -> Triple(test, currentSession, owner) }
                .collect { (test, currentSession, owner) ->
                    if (test != null && historyOwnedByCurrentSession(currentSession, owner) && (!test.running || test.sent % 10 == 0)) {
                        dao.upsertFieldTest(test.toEntity())
                    }
                }
        }
        scope.launch {
            session.filterNotNull().collect { secureSession ->
                if (secureSession.authenticationState != AuthenticationState.AUTHENTICATED) return@collect
                val identity = secureSession.localNodeIdentity
                val previousHistoryOwner = settingsStore.localHistoryOwnerNodeId.first()
                if (previousHistoryOwner != null && previousHistoryOwner != identity.nodeId) clearSessionSensitiveHistory()
                if (previousHistoryOwner != identity.nodeId) settingsStore.setLocalHistoryOwnerNodeId(identity.nodeId)

                if (transportMode.value == TransportMode.BLE && settings.value.rememberTrustedNode) {
                    val connected = connectionState.value as? MeshConnectionState.Connected
                    val address = connected?.device?.takeIf { it.secureMeshNodeId == identity.nodeId }?.address
                    dao.upsertTrustedDevice(
                        TrustedDeviceEntity(
                            nodeId = identity.nodeId,
                            displayName = identity.displayName,
                            lastSeenBleAddress = address,
                            trustedAtEpochMs = now(),
                            firmwareVersion = identity.firmwareVersion,
                            protocolVersion = identity.protocolVersion,
                        )
                    )
                }
            }
        }
    }

    private fun historyOwnedByCurrentSession(session: SecureMeshSession?, ownerNodeId: NodeId?): Boolean =
        session?.authenticationState == AuthenticationState.AUTHENTICATED &&
            ownerNodeId != null && session.localNodeIdentity.nodeId == ownerNodeId

    override fun observeEvents(): Flow<List<MeshEvent>> = combine(dao.observeEvents(), session, localHistoryOwnerNodeId) { list, currentSession, owner ->
        if (!historyOwnedByCurrentSession(currentSession, owner)) emptyList() else list.map { event -> event.toDomain() }
    }
    override fun observeFieldTestHistory(): Flow<List<FieldTestSession>> = combine(dao.observeFieldTests(), session, localHistoryOwnerNodeId) { list, currentSession, owner ->
        if (!historyOwnedByCurrentSession(currentSession, owner)) emptyList() else list.map { test -> test.toDomain() }
    }
    override fun observePositionHistory(nodeId: NodeId?): Flow<List<NodePosition>> = combine(dao.observePositions(nodeId), session, localHistoryOwnerNodeId) { list, currentSession, owner ->
        if (!historyOwnedByCurrentSession(currentSession, owner)) emptyList() else list.map { position -> position.toDomain() }
    }

    override suspend fun useTransport(mode: TransportMode) = router.switchTo(mode)
    override suspend fun launchDemo(profile: DemoProfile) {
        router.switchTo(TransportMode.MOCK)
        mockTransport.launchDemo(profile)
        withTimeout(2_000L) {
            combine(demoProfile, session, nodes, connectionState) { activeProfile, activeSession, activeNodes, state ->
                activeProfile == profile &&
                    activeSession?.authenticationState == AuthenticationState.AUTHENTICATED &&
                    activeNodes.isNotEmpty() &&
                    state is MeshConnectionState.Connected
            }.first { it }
        }
    }
    override suspend fun applyDemoScenario(name: String) { if (transportMode.value == TransportMode.MOCK) mockTransport.applyScenario(name) }
    override suspend fun scanDevices(durationMs: Long?) { router.current().startScan((durationMs ?: settings.value.scanDurationSec * 1000L).coerceIn(5_000, 30_000)) }
    override suspend fun stopScan() = router.current().stopScan()
    override suspend fun connect(device: DiscoveredDevice) = router.current().connect(device)
    override suspend fun disconnect() = router.current().disconnect()

    override suspend fun cancelReconnect() {
        reconnectJob?.cancel(); reconnectJob = null; reconnectOverride.value = null; router.current().stopScan()
    }

    override suspend fun attemptAutoReconnect() {
        if (!settings.value.autoReconnect) return
        val trusted = dao.latestTrustedDevice() ?: return
        // The protocol intentionally does not advertise nodeId. A remembered BLE address is only a transport hint;
        // authenticated INFO must still prove the stable nodeId after every reconnect.
        val addressHint = trusted.lastSeenBleAddress ?: return
        router.switchTo(TransportMode.BLE)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delays = listOf(0L, 900L, 2_000L)
            for ((index, backoff) in delays.withIndex()) {
                delay(backoff)
                reconnectOverride.value = MeshConnectionState.Reconnecting(trusted.nodeId, index + 1, backoff)
                router.ble.startScan(5_000)
                val found = withTimeoutOrNull(5_500L) {
                    router.ble.discoveredDevices
                        .map { devices -> devices.firstOrNull { it.address.equals(addressHint, ignoreCase = true) && it.classification != DeviceClassification.UNKNOWN_BLE } }
                        .filterNotNull().first()
                }
                router.ble.stopScan()
                if (found == null) continue

                reconnectOverride.value = null
                router.ble.connect(found)
                val verified = withTimeoutOrNull(18_000L) {
                    router.ble.session.filterNotNull().first { it.authenticationState == AuthenticationState.AUTHENTICATED }
                }
                if (verified?.localNodeIdentity?.nodeId == trusted.nodeId) return@launch
                if (verified != null && verified.localNodeIdentity.nodeId != trusted.nodeId) {
                    // Address reuse/rotation can happen; never transfer trust to the different authenticated node.
                    dao.upsertTrustedDevice(trusted.copy(lastSeenBleAddress = null))
                    router.ble.disconnect()
                    return@launch
                }
                router.ble.disconnect()
            }
            reconnectOverride.value = null
        }
    }

    override suspend fun sendMessage(destination: NodeId, payload: String) = router.current().sendMessage(destination, payload)
    override suspend fun addStaticRoute(destination: NodeId, via: NodeId) = router.current().addStaticRoute(destination, via)
    override suspend fun removeRoute(destination: NodeId) = router.current().removeRoute(destination)
    override suspend fun startFieldTest(config: FieldTestConfig) = router.current().startFieldTest(config)
    override suspend fun stopFieldTest() = router.current().stopFieldTest()
    override suspend fun acknowledgeSos(id: String) = router.current().acknowledgeSos(id)
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.write(transform(settings.value))
    private suspend fun clearSessionSensitiveHistory() {
        dao.clearEvents(); dao.clearMessages(); dao.clearKnownNodes(); dao.clearFieldTests(); dao.clearPositions()
    }
    override suspend fun clearLocalHistory() = clearSessionSensitiveHistory()

    override suspend fun exportEventsCsv(): String = observeEvents().first().joinToString("\n", "timestamp,category,node,title,details\n") { e -> listOf(e.timestampEpochMs,e.category,e.nodeId.orEmpty(),e.title,e.details).joinToString(",") { csv(it.toString()) } }
    override suspend fun exportEventsJson(): String = observeEvents().first().joinToString(",\n", "[\n", "\n]") { e -> "  {\"id\":${json(e.id)},\"timestamp\":${e.timestampEpochMs},\"category\":${json(e.category.name)},\"nodeId\":${jsonNullable(e.nodeId)},\"title\":${json(e.title)},\"details\":${json(e.details)}}" }
    override suspend fun exportFieldTestsCsv(): String = observeFieldTestHistory().first().joinToString("\n", "id,source,target,mode,sent,confirmedReceived,confirmedLost,pdr,retries,route\n") { t -> listOf(t.id,t.config.source,t.config.target,t.config.mode,t.sent,t.confirmedReceived?.toString() ?: "UNKNOWN",t.confirmedLost?.toString() ?: "UNKNOWN",t.pdr?.toString() ?: "UNKNOWN",t.retries,t.route.joinToString("->")).joinToString(",") { csv(it.toString()) } }
    override suspend fun exportFieldTestsJson(): String = observeFieldTestHistory().first().joinToString(",\n", "[\n", "\n]") { t -> "  {\"id\":${json(t.id)},\"source\":${json(t.config.source)},\"target\":${json(t.config.target)},\"mode\":${json(t.config.mode.name)},\"sent\":${t.sent},\"confirmedReceived\":${t.confirmedReceived ?: "null"},\"confirmedLost\":${t.confirmedLost ?: "null"},\"pdr\":${t.pdr ?: "null"},\"retries\":${t.retries},\"route\":${json(t.route.joinToString("->"))}}" }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun json(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    private fun jsonNullable(value: String?) = value?.let(::json) ?: "null"
}

private fun MeshEvent.toEntity() = EventEntity(id,timestampEpochMs,category.name,title,details,nodeId)
private fun EventEntity.toDomain() = MeshEvent(id,timestampEpochMs,EventCategory.valueOf(category),title,details,nodeId)
private fun MeshMessage.toEntity() = MessageEntity(id,origin,destination,payload,createdAtEpochMs,progressState.name,observedRoute().joinToString(">"),hopTrace.size,totalRetries() ?: 0,deliveredAtEpochMs,failureReason)
private fun MeshNode.toEntity() = KnownNodeEntity(id,name,role.name,firmwareVersion ?: "UNKNOWN",protocolVersion ?: -1,lastSeenEpochMs)
private fun NodePosition.toEntity() = PositionEntity("$nodeId:$timestampEpochMs",nodeId,latitude,longitude,timestampEpochMs,satellites ?: -1,hdop,speedMps,valid)
private fun PositionEntity.toDomain() = NodePosition(nodeId,latitude,longitude,timestampEpochMs,satellites.takeIf { it >= 0 },hdop,speedMps,valid)
private fun FieldTestSession.toEntity() = FieldTestEntity(id,config.source,config.target,config.mode.name,config.packetCount,config.intervalMs,config.payloadBytes,startedAtEpochMs,finishedAtEpochMs,sent,confirmedReceived ?: -1,confirmedLost ?: -1,retries,route.joinToString(">"))
private fun FieldTestEntity.toDomain() = FieldTestSession(id,FieldTestConfig(source,target,FieldTestMode.valueOf(mode),packetCount,intervalMs,payloadBytes),startedAtEpochMs,finishedAtEpochMs,sent,received.takeIf { it >= 0 },lost.takeIf { it >= 0 },retries,route.split(">").filter(String::isNotBlank),emptyList(),finishedAtEpochMs==null)
