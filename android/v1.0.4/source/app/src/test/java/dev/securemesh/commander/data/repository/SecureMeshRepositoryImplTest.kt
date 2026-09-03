package dev.securemesh.commander.data.repository

import dev.securemesh.commander.core.database.*
import dev.securemesh.commander.core.settings.SettingsDataSource
import dev.securemesh.commander.core.security.SensitiveDataProtector
import dev.securemesh.commander.data.mock.MockTransport
import dev.securemesh.commander.data.transport.TransportRouter
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SecureMeshRepositoryImplTest {
    @Test fun `repository preserves transport boundary and current firmware truth`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings()
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings, FakeProtector)

        repository.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            assertEquals(TransportMode.MOCK, repository.transportMode.value)
            assertEquals(DemoProfile.CURRENT_FIRMWARE_V05, repository.demoProfile.value)
            assertEquals(4, repository.nodes.value.size)
            assertTrue(repository.connectionState.value is MeshConnectionState.Connected)
            assertNotNull(repository.session.value)

            // StateFlow fan-out is asynchronous. Await the route snapshot instead of
            // depending on coroutine scheduling order between eager repository flows.
            val target = withTimeout(2_000L) {
                repository.routes
                    .first { routes -> routes.any { it.type == RouteType.STATIC } }
                    .first { it.type == RouteType.STATIC }
                    .destination
            }
            val id = repository.sendMessage(target, "repository test").getOrThrow()
            delay(1_250)
            val message = withTimeout(2_000L) {
                repository.messages
                    .first { messages -> messages.any { it.id == id } }
                    .first { it.id == id }
            }
            assertEquals(MessageDeliveryState.FINAL_CONFIRMATION_PENDING, message.progressState)
            assertEquals(MessageFinalState.UNKNOWN, message.finalState)
            delay(150)
            assertTrue(dao.events.value.isNotEmpty())
        } finally {
            mock.stop(); ble.stop()
        }
    }

    @Test fun `identity switch clears operational history but preserves stored chats`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings(initialOwner = "SM-OLD")
        dao.upsertEvents(listOf(EventEntity("old-event", 1L, EventCategory.SYSTEM.name, "Old owner", "must not leak", "SM-OLD")))
        dao.upsertMessages(
            listOf(
                MessageEntity(
                    key = "SM-OLD:old-message",
                    id = "old-message",
                    origin = "SM-OLD",
                    destination = "SM-PEER",
                    payload = "must remain on device",
                    createdAtEpochMs = 2L,
                    state = MessageDeliveryState.DELIVERED.name,
                    route = "SM-OLD>SM-PEER",
                    hops = 1,
                    retries = 0,
                    deliveredAtEpochMs = 3L,
                    failureReason = null,
                )
            )
        )
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings, FakeProtector)

        repository.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            delay(250)
            assertEquals("SM-7C21", settings.ownerValue)
            assertEquals("new-session data must not be written into the previous owner history", 0, dao.eventWritesBeforeFirstClear)
            assertFalse(dao.events.value.any { it.id == "old-event" })
            assertTrue("chat rows for another local node must survive switching", dao.messages.value.any { it.id == "old-message" })
            assertTrue("the new local node must not see the old node chat", repository.observeMessageHistory().first().none { it.id == "old-message" })
        } finally {
            mock.stop(); ble.stop()
        }
    }

    @Test fun `stored message history is restored without live transport messages`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings(initialOwner = "SM-LOCAL")
        dao.upsertMessages(
            listOf(
                MessageEntity(
                    key = "SM-LOCAL:persisted-1",
                    id = "persisted-1",
                    origin = "SM-LOCAL",
                    destination = "SM-REMOTE",
                    payload = "survives restart",
                    createdAtEpochMs = 100L,
                    state = MessageDeliveryState.DELIVERED.name,
                    route = "SM-LOCAL>SM-REMOTE",
                    hops = 1,
                    retries = 0,
                    deliveredAtEpochMs = 150L,
                    failureReason = null,
                )
            )
        )
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings, FakeProtector)

        try {
            val restored = withTimeout(1_000L) {
                repository.observeMessageHistory().first { list -> list.any { it.id == "persisted-1" } }
            }
            val message = restored.first { it.id == "persisted-1" }
            assertEquals("survives restart", message.payload)
            assertEquals(MessageFinalState.DELIVERED, message.finalState)
            assertEquals(listOf("SM-LOCAL", "SM-REMOTE"), message.observedRoute())
        } finally {
            mock.stop(); ble.stop()
        }
    }

    @Test fun `same firmware message id from different origins does not collide`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings(initialOwner = "SM-LOCAL")
        dao.upsertMessages(
            listOf(
                MessageEntity("A:0000002A", "0000002A", "A", "SM-LOCAL", "from A", 10L, MessageDeliveryState.DELIVERED.name, "A>SM-LOCAL", 1, 0, 11L, null),
                MessageEntity("B:0000002A", "0000002A", "B", "SM-LOCAL", "from B", 20L, MessageDeliveryState.DELIVERED.name, "B>SM-LOCAL", 1, 0, 21L, null),
            )
        )
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings, FakeProtector)
        try {
            val restored = repository.observeMessageHistory().first { it.size == 2 }
            assertEquals(setOf("from A", "from B"), restored.map { it.payload }.toSet())
            assertEquals(2, restored.map { it.stableKey() }.distinct().size)
        } finally {
            mock.stop(); ble.stop()
        }
    }

    @Test fun `disabling remembered trust deletes saved reconnect identity`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings(initialSettings = AppSettings(rememberTrustedNode = true, autoReconnect = true))
        dao.upsertTrustedDevice(
            TrustedDeviceEntity("A1B2C3D4", "Node", "AA:BB:CC:DD:EE:FF", 10L, "0.8.3", 1)
        )
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings, FakeProtector)
        try {
            repository.updateSettings { it.copy(rememberTrustedNode = false) }
            assertFalse(settings.settingsValue.autoReconnect)
            assertNull(dao.latestTrustedDevice())
            repository.attemptAutoReconnect()
            assertEquals(TransportMode.BLE, repository.transportMode.value)
        } finally {
            mock.stop(); ble.stop()
        }
    }

    @Test fun `trusted record uses SecureMesh node identity and BLE address is metadata`() = runBlocking {
        val dao = FakeDao()
        val trusted = TrustedDeviceEntity(
            nodeId = "A1B2C3D4",
            displayName = "Node A1B2C3D4",
            lastSeenBleAddress = "AA:BB:CC:DD:EE:FF",
            trustedAtEpochMs = 10L,
            firmwareVersion = "0.6.1",
            protocolVersion = 1,
        )
        dao.upsertTrustedDevice(trusted)
        assertEquals("A1B2C3D4", dao.latestTrustedDevice()?.nodeId)
        assertEquals("AA:BB:CC:DD:EE:FF", dao.trustedDevice("A1B2C3D4")?.lastSeenBleAddress)
        assertNull(dao.trustedDevice("AA:BB:CC:DD:EE:FF"))
    }
}

private object FakeProtector : SensitiveDataProtector {
    private const val PREFIX = "testenc:"
    override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
    override fun encryptString(plainText: String, aad: String): String = PREFIX + plainText
    override fun decryptString(value: String, aad: String): String = value.removePrefix(PREFIX)
}

private class FakeSettings(
    initialOwner: NodeId? = null,
    initialSettings: AppSettings = AppSettings(),
) : SettingsDataSource {
    private val state = MutableStateFlow(initialSettings)
    private val owner = MutableStateFlow(initialOwner)
    val ownerValue: NodeId? get() = owner.value
    val settingsValue: AppSettings get() = state.value
    override val settings: Flow<AppSettings> = state
    override val localHistoryOwnerNodeId: Flow<NodeId?> = owner
    override suspend fun write(settings: AppSettings) { state.value = settings }
    override suspend fun setLocalHistoryOwnerNodeId(nodeId: NodeId?) { owner.value = nodeId }
}

private class FakeDao : SecureMeshDao {
    val events = MutableStateFlow<List<EventEntity>>(emptyList())
    val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    var eventWritesBeforeFirstClear = 0
    private var historyClearedAtLeastOnce = false
    private val tests = MutableStateFlow<List<FieldTestEntity>>(emptyList())
    private val positions = MutableStateFlow<List<PositionEntity>>(emptyList())
    private val trusted = linkedMapOf<String, TrustedDeviceEntity>()
    override suspend fun upsertEvents(items: List<EventEntity>) {
        if (!historyClearedAtLeastOnce && events.value.isNotEmpty()) eventWritesBeforeFirstClear++
        events.value = (items + events.value).distinctBy { it.id }
    }
    override fun observeEvents(limit: Int): Flow<List<EventEntity>> = events.map { it.take(limit) }
    override suspend fun upsertMessages(items: List<MessageEntity>) { messages.value = (items + messages.value).distinctBy { it.key } }
    override suspend fun loadMessages(): List<MessageEntity> = messages.value
    override fun observeMessages(limit: Int): Flow<List<MessageEntity>> = messages.map { it.take(limit) }
    override fun observeMessagesForNode(nodeId: String, limit: Int): Flow<List<MessageEntity>> =
        messages.map { rows -> rows.filter { it.origin == nodeId || it.destination == nodeId }.take(limit) }
    override suspend fun upsertKnownNodes(items: List<KnownNodeEntity>) = Unit
    override suspend fun upsertFieldTest(item: FieldTestEntity) { tests.value = listOf(item) + tests.value.filterNot { it.id == item.id } }
    override fun observeFieldTests(limit: Int): Flow<List<FieldTestEntity>> = tests.map { it.take(limit) }
    override suspend fun upsertPositions(items: List<PositionEntity>) { positions.value = (items + positions.value).distinctBy { it.key } }
    override fun observePositions(nodeId: String?, limit: Int): Flow<List<PositionEntity>> = positions.map { list -> list.filter { nodeId == null || it.nodeId == nodeId }.take(limit) }
    private val contacts = MutableStateFlow<List<ContactProfileEntity>>(emptyList())
    override suspend fun upsertContactProfile(item: ContactProfileEntity) { contacts.value = listOf(item) + contacts.value.filterNot { it.nodeId == item.nodeId } }
    override fun observeContactProfiles(): Flow<List<ContactProfileEntity>> = contacts
    override suspend fun deleteContactProfile(nodeId: String) { contacts.value = contacts.value.filterNot { it.nodeId == nodeId } }
    override suspend fun upsertTrustedDevice(item: TrustedDeviceEntity) { trusted[item.nodeId] = item }
    override suspend fun latestTrustedDevice(): TrustedDeviceEntity? = trusted.values.maxByOrNull { it.trustedAtEpochMs }
    override suspend fun trustedDevice(nodeId: String): TrustedDeviceEntity? = trusted[nodeId]
    override suspend fun clearTrustedDevices() { trusted.clear() }
    override suspend fun deleteEventsBefore(cutoff: Long) { events.value = events.value.filter { it.timestampEpochMs >= cutoff } }
    override suspend fun deletePositionsBefore(cutoff: Long) { positions.value = positions.value.filter { it.timestampEpochMs >= cutoff } }
    override suspend fun clearEvents() { historyClearedAtLeastOnce = true; events.value = emptyList() }
    override suspend fun clearMessages() { messages.value = emptyList() }
    override suspend fun clearMessagesForNode(nodeId: String) { messages.value = messages.value.filterNot { it.origin == nodeId || it.destination == nodeId } }
    override suspend fun clearKnownNodes() = Unit
    override suspend fun clearFieldTests() { tests.value = emptyList() }
    override suspend fun clearPositions() { positions.value = emptyList() }
}
