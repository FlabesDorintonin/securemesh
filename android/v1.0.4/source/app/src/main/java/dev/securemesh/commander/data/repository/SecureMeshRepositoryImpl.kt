package dev.securemesh.commander.data.repository

import dev.securemesh.commander.core.database.*
import dev.securemesh.commander.core.settings.SettingsDataSource
import dev.securemesh.commander.core.security.SensitiveDataProtector
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
    private val sensitiveDataCipher: SensitiveDataProtector,
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
    override val deviceUiState = activeFlow { it.deviceUiState }.stateIn(scope, SharingStarted.Eagerly, null)
    override val oledFramebuffer = activeFlow { it.oledFramebuffer }.stateIn(scope, SharingStarted.Eagerly, null)
    override val knownNodeIds = activeFlow { it.knownNodeIds }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val networkManifest = activeFlow { it.networkManifest }.stateIn(scope, SharingStarted.Eagerly, null)
    override val vanguardDiagnostics = activeFlow { it.vanguardDiagnostics }.stateIn(scope, SharingStarted.Eagerly, null)
    override val labLinkPolicies = activeFlow { it.labLinkPolicies }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val settings = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    override val localHistoryOwnerNodeId = settingsStore.localHistoryOwnerNodeId.stateIn(scope, SharingStarted.Eagerly, null)
    override val contactProfiles: StateFlow<Map<NodeId, ContactProfile>> = dao.observeContactProfiles()
        .map { rows -> rows.mapNotNull { it.toDomain(sensitiveDataCipher) }.associateBy(ContactProfile::nodeId) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val liveEvents = activeFlow { it.events }

    init {
        scope.launch { hardenLegacyMessagePayloads() }
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
                        dao.upsertMessages(items.map { message -> message.toEntity(sensitiveDataCipher) })
                    }
                }
        }
        scope.launch {
            combine(nodes, session, localHistoryOwnerNodeId) { list, currentSession, owner -> Triple(list, currentSession, owner) }
                .collect { (list, currentSession, owner) ->
                    if (!historyOwnedByCurrentSession(currentSession, owner)) return@collect
                    if (list.isNotEmpty()) dao.upsertKnownNodes(list.map { node -> node.toEntity() })
                    if (settings.value.positionHistory) {
                        val positions = list.mapNotNull { it.position }.map { position -> position.toEntity(sensitiveDataCipher) }
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
                if (previousHistoryOwner != null && previousHistoryOwner != identity.nodeId) {
                    // Operational telemetry is session-scoped. Chat rows are different: origin/destination already
                    // partition them by SecureMesh identity, so switching the attached ESP32 must not destroy chats.
                    clearSessionSensitiveHistory(preserveMessages = true)
                }
                if (previousHistoryOwner != identity.nodeId) settingsStore.setLocalHistoryOwnerNodeId(identity.nodeId)

                if (transportMode.value == TransportMode.BLE && settings.value.rememberTrustedNode) {
                    // BleTransport publishes the authenticated session immediately after INFO validation. Diagnostics already
                    // knows the transport address before that, so use it as the stable source of optional transport metadata.
                    val diagnosticAddress = bleDiagnostics.value?.bleAddress
                    val connected = connectionState.value as? MeshConnectionState.Connected
                    val connectedAddress = connected?.device?.takeIf { it.secureMeshNodeId == identity.nodeId }?.address
                    dao.upsertTrustedDevice(
                        TrustedDeviceEntity(
                            nodeId = identity.nodeId,
                            displayName = identity.displayName,
                            lastSeenBleAddress = diagnosticAddress ?: connectedAddress,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessageHistory(): Flow<List<MeshMessage>> =
        localHistoryOwnerNodeId.flatMapLatest { owner ->
            if (owner == null) {
                flowOf(emptyList<MeshMessage>())
            } else {
                combine(dao.observeMessagesForNode(owner), messages) { stored, live ->
                    val persisted = stored.map { it.toDomain(sensitiveDataCipher) }
                    val liveForOwner = live.filter { it.origin == owner || it.destination == owner }
                    (persisted + liveForOwner)
                        .associateBy { it.stableKey() }
                        .values
                        .sortedByDescending { it.createdAtEpochMs }
                }
            }
        }

    override fun observeEvents(): Flow<List<MeshEvent>> = combine(dao.observeEvents(), session, localHistoryOwnerNodeId) { list, currentSession, owner ->
        if (!historyOwnedByCurrentSession(currentSession, owner)) emptyList() else list.map { event -> event.toDomain() }
    }
    override fun observeFieldTestHistory(): Flow<List<FieldTestSession>> = combine(dao.observeFieldTests(), session, localHistoryOwnerNodeId) { list, currentSession, owner ->
        if (!historyOwnedByCurrentSession(currentSession, owner)) emptyList() else list.map { test -> test.toDomain() }
    }
    override fun observePositionHistory(nodeId: NodeId?): Flow<List<NodePosition>> = combine(dao.observePositions(nodeId), session, localHistoryOwnerNodeId) { list, currentSession, owner ->
        if (!historyOwnedByCurrentSession(currentSession, owner)) emptyList() else list.mapNotNull { position -> position.toDomain(sensitiveDataCipher) }
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
        if (!settings.value.autoReconnect || !settings.value.rememberTrustedNode) return
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

                // Do not keep retrying while Android is waiting for permission/Bluetooth state.
                // This is especially important on OEM firmware that aggressively pauses/recreates
                // the activity around the Nearby devices permission dialog.
                when (router.ble.connectionState.value) {
                    is MeshConnectionState.PermissionRequired,
                    MeshConnectionState.BluetoothDisabled,
                    MeshConnectionState.BluetoothUnavailable -> {
                        reconnectOverride.value = null
                        return@launch
                    }
                    else -> Unit
                }

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
    override suspend fun raiseSos(type: Int) = router.current().raiseSos(type)
    override suspend fun sendCommandNotice(destination: NodeId, kind: CommandNoticeKind, target: NodePosition?) = router.current().sendCommandNotice(destination, kind, target)
    override suspend fun refreshDeviceUiState() = router.current().refreshDeviceUiState()
    override suspend fun sendDeviceUiAction(action: DeviceUiAction) = router.current().sendDeviceUiAction(action)
    override suspend fun refreshOledFramebuffer() = router.current().refreshOledFramebuffer()
    override suspend fun refreshVanguardState() = router.current().refreshVanguardState()
    override suspend fun setManifest(epoch: Long, nodes: List<NodeId>) = router.current().setManifest(epoch, nodes)
    override suspend fun discoverRoute(destination: NodeId, forceFresh: Boolean) = router.current().discoverRoute(destination, forceFresh)
    override suspend fun clearDynamicRoutes() = router.current().clearDynamicRoutes()
    override suspend fun injectLinkFailure(peer: NodeId, durationMs: Long) = router.current().injectLinkFailure(peer, durationMs)
    override suspend fun setLabLinkPolicy(peer: NodeId, preset: LabLinkPreset, durationMs: Long) = router.current().setLabLinkPolicy(peer, preset, durationMs)
    override suspend fun clearBleRadar() = router.current().clearBleRadar()
    override suspend fun updateContactProfile(nodeId: NodeId, alias: String?, note: String?, notePinned: Boolean) {
        val cleanAlias = alias?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
        val cleanNote = note?.trim()?.take(2_000)?.takeIf { it.isNotEmpty() }
        if (cleanAlias == null && cleanNote == null && !notePinned) {
            dao.deleteContactProfile(nodeId)
            return
        }
        val profile = ContactProfile(nodeId, cleanAlias, cleanNote, notePinned && cleanNote != null, now())
        dao.upsertContactProfile(profile.toEntity(sensitiveDataCipher))
    }

    override suspend fun clearContactProfile(nodeId: NodeId) {
        dao.deleteContactProfile(nodeId)
    }

    private suspend fun hardenLegacyMessagePayloads() {
        val legacy = dao.loadMessages().filterNot { sensitiveDataCipher.isEncrypted(it.payload) }
        if (legacy.isEmpty()) return
        val hardened = legacy.map { row ->
            row.copy(payload = sensitiveDataCipher.encryptString(row.payload, "message:${row.key}"))
        }
        dao.upsertMessages(hardened)
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val previous = settings.value
        val requested = transform(previous)
        val updated = if (!requested.rememberTrustedNode) requested.copy(autoReconnect = false) else requested
        settingsStore.write(updated)
        if (previous.rememberTrustedNode && !updated.rememberTrustedNode) dao.clearTrustedDevices()
        if (previous.autoReconnect && !updated.autoReconnect) cancelReconnect()
    }

    private suspend fun clearSessionSensitiveHistory(preserveMessages: Boolean = false) {
        dao.clearEvents()
        if (!preserveMessages) dao.clearMessages()
        dao.clearKnownNodes()
        dao.clearFieldTests()
        dao.clearPositions()
    }

    override suspend fun clearLocalHistory() {
        val owner = localHistoryOwnerNodeId.value
        clearSessionSensitiveHistory(preserveMessages = true)
        if (owner == null) dao.clearMessages() else dao.clearMessagesForNode(owner)
    }

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
private fun MeshMessage.toEntity(cipher: SensitiveDataProtector) = MessageEntity(
    key = stableKey(),
    id = id,
    origin = origin,
    destination = destination,
    payload = cipher.encryptString(payload, "message:${stableKey()}"),
    createdAtEpochMs = createdAtEpochMs,
    state = progressState.name,
    route = observedRoute().joinToString(">"),
    hops = hopTrace.size,
    retries = totalRetries() ?: 0,
    deliveredAtEpochMs = deliveredAtEpochMs,
    failureReason = failureReason,
)
private fun MessageEntity.toDomain(cipher: SensitiveDataProtector): MeshMessage {
    val progress = runCatching { MessageDeliveryState.valueOf(state) }.getOrDefault(MessageDeliveryState.QUEUED)
    val routeNodes = route.split(">").filter(String::isNotBlank)
    val syntheticHops = routeNodes.zipWithNext().mapIndexed { index, pair ->
        TransmissionHop(
            from = pair.first,
            to = pair.second,
            frameId = null,
            ackState = HopAckState.UNAVAILABLE,
            retries = this.retries.takeIf { index == routeNodes.size - 2 && it > 0 },
            rssi = null,
            snr = null,
            timestampEpochMs = createdAtEpochMs,
        )
    }
    val finalState = when {
        deliveredAtEpochMs != null || progress == MessageDeliveryState.DELIVERED -> MessageFinalState.DELIVERED
        progress == MessageDeliveryState.FAILED -> MessageFinalState.FAILED
        progress == MessageDeliveryState.EXPIRED -> MessageFinalState.EXPIRED
        progress == MessageDeliveryState.FINAL_CONFIRMATION_PENDING -> MessageFinalState.UNKNOWN
        else -> MessageFinalState.PENDING
    }
    return MeshMessage(
        id = id,
        origin = origin,
        destination = destination,
        payload = runCatching { cipher.decryptString(payload, "message:$key") }.getOrElse { "[Зашифрованные данные недоступны]" },
        createdAtEpochMs = createdAtEpochMs,
        progressState = progress,
        finalState = finalState,
        hopTrace = syntheticHops,
        deliveredAtEpochMs = deliveredAtEpochMs,
        failureReason = failureReason,
    )
}
private fun ContactProfile.toEntity(cipher: SensitiveDataProtector) = ContactProfileEntity(
    nodeId = nodeId,
    encryptedAlias = alias?.let { cipher.encryptString(it, "contact:$nodeId:alias") },
    encryptedNote = note?.let { cipher.encryptString(it, "contact:$nodeId:note") },
    notePinned = notePinned,
    updatedAtEpochMs = updatedAtEpochMs,
)
private fun ContactProfileEntity.toDomain(cipher: SensitiveDataProtector): ContactProfile? = runCatching {
    ContactProfile(
        nodeId = nodeId,
        alias = encryptedAlias?.let { cipher.decryptString(it, "contact:$nodeId:alias") },
        note = encryptedNote?.let { cipher.decryptString(it, "contact:$nodeId:note") },
        notePinned = notePinned,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}.getOrNull()

private fun MeshNode.toEntity() = KnownNodeEntity(id,name,role.name,firmwareVersion ?: "UNKNOWN",protocolVersion ?: -1,lastSeenEpochMs)
private fun NodePosition.toEntity(cipher: SensitiveDataProtector): PositionEntity {
    val key = "$nodeId:$timestampEpochMs"
    val plain = listOf(
        latitude.toString(),
        longitude.toString(),
        (satellites ?: -1).toString(),
        hdop?.toString().orEmpty(),
        speedMps?.toString().orEmpty(),
        if (valid) "1" else "0",
    ).joinToString("|")
    return PositionEntity(key, nodeId, timestampEpochMs, cipher.encryptString(plain, "position:$key"))
}
private fun PositionEntity.toDomain(cipher: SensitiveDataProtector): NodePosition? = runCatching {
    val parts = cipher.decryptString(encryptedPayload, "position:$key").split('|')
    require(parts.size == 6)
    NodePosition(
        nodeId = nodeId,
        latitude = parts[0].toDouble(),
        longitude = parts[1].toDouble(),
        timestampEpochMs = timestampEpochMs,
        satellites = parts[2].toInt().takeIf { it >= 0 },
        hdop = parts[3].takeIf(String::isNotBlank)?.toDouble(),
        speedMps = parts[4].takeIf(String::isNotBlank)?.toDouble(),
        valid = parts[5] == "1",
    )
}.getOrNull()
private fun FieldTestSession.toEntity() = FieldTestEntity(id,config.source,config.target,config.mode.name,config.packetCount,config.intervalMs,config.payloadBytes,startedAtEpochMs,finishedAtEpochMs,sent,confirmedReceived ?: -1,confirmedLost ?: -1,retries,route.joinToString(">"))
private fun FieldTestEntity.toDomain() = FieldTestSession(id,FieldTestConfig(source,target,FieldTestMode.valueOf(mode),packetCount,intervalMs,payloadBytes),startedAtEpochMs,finishedAtEpochMs,sent,received.takeIf { it >= 0 },lost.takeIf { it >= 0 },retries,route.split(">").filter(String::isNotBlank),emptyList(),finishedAtEpochMs==null)
