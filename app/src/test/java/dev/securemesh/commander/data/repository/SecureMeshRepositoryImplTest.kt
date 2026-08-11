package dev.securemesh.commander.data.repository

import dev.securemesh.commander.core.database.*
import dev.securemesh.commander.core.settings.SettingsDataSource
import dev.securemesh.commander.data.mock.MockTransport
import dev.securemesh.commander.data.transport.TransportRouter
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SecureMeshRepositoryImplTest {
    @Test fun `repository preserves transport boundary and current firmware truth`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings()
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings)

        repository.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            assertEquals(TransportMode.MOCK, repository.transportMode.value)
            assertEquals(DemoProfile.CURRENT_FIRMWARE_V05, repository.demoProfile.value)
            assertEquals(4, repository.nodes.value.size)
            assertTrue(repository.connectionState.value is MeshConnectionState.Connected)
            assertNotNull(repository.session.value)

            val target = repository.routes.value.first { it.type == RouteType.STATIC }.destination
            val id = repository.sendMessage(target, "repository test").getOrThrow()
            delay(1_250)
            val message = repository.messages.value.first { it.id == id }
            assertEquals(MessageDeliveryState.FINAL_CONFIRMATION_PENDING, message.progressState)
            assertEquals(MessageFinalState.UNKNOWN, message.finalState)
            delay(150)
            assertTrue(dao.events.value.isNotEmpty())
        } finally {
            mock.stop(); ble.stop()
        }
    }


    @Test fun `local history is cleared when authenticated local node identity changes`() = runBlocking {
        val mock = MockTransport()
        val ble = MockTransport()
        val dao = FakeDao()
        val settings = FakeSettings(initialOwner = "SM-OLD")
        dao.upsertEvents(listOf(EventEntity("old-event", 1L, EventCategory.SYSTEM.name, "Old owner", "must not leak", "SM-OLD")))
        val repository = SecureMeshRepositoryImpl(TransportRouter(mock, ble), mock, dao, settings)

        repository.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            delay(250)
            assertEquals("SM-7C21", settings.ownerValue)
            assertEquals("new-session data must not be written into the previous owner history", 0, dao.eventWritesBeforeFirstClear)
            assertFalse(dao.events.value.any { it.id == "old-event" })
            assertTrue(repository.observeEvents().first().none { it.id == "old-event" })
        } finally {
            mock.stop(); ble.stop()
        }
    }

    @Test fun `trusted record uses SecureMesh node identity`() = runBlocking {
        val dao = FakeDao()
        val trusted = TrustedDeviceEntity("SM-IDENTITY-77", "Node 77", 10L, 1)
        dao.upsertTrustedDevice(trusted)
        assertEquals("SM-IDENTITY-77", dao.latestTrustedDevice()?.nodeId)
    }
}

private class FakeSettings(initialOwner: NodeId? = null) : SettingsDataSource {
    private val state = MutableStateFlow(AppSettings())
    private val owner = MutableStateFlow(initialOwner)
    val ownerValue: NodeId? get() = owner.value
    override val settings: Flow<AppSettings> = state
    override val localHistoryOwnerNodeId: Flow<NodeId?> = owner
    override suspend fun write(settings: AppSettings) { state.value = settings }
    override suspend fun setLocalHistoryOwnerNodeId(nodeId: NodeId?) { owner.value = nodeId }
}

private class FakeDao : SecureMeshDao {
    val events = MutableStateFlow<List<EventEntity>>(emptyList())
    var eventWritesBeforeFirstClear = 0
    private var historyClearedAtLeastOnce = false
    private val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val tests = MutableStateFlow<List<FieldTestEntity>>(emptyList())
    private val positions = MutableStateFlow<List<PositionEntity>>(emptyList())
    private var trusted: TrustedDeviceEntity? = null
    override suspend fun upsertEvents(items: List<EventEntity>) {
        if (!historyClearedAtLeastOnce && events.value.isNotEmpty()) eventWritesBeforeFirstClear++
        events.value = (items + events.value).distinctBy { it.id }
    }
    override fun observeEvents(limit: Int): Flow<List<EventEntity>> = events.map { it.take(limit) }
    override suspend fun upsertMessages(items: List<MessageEntity>) { messages.value = (items + messages.value).distinctBy { it.id } }
    override fun observeMessages(limit: Int): Flow<List<MessageEntity>> = messages.map { it.take(limit) }
    override suspend fun upsertKnownNodes(items: List<KnownNodeEntity>) = Unit
    override suspend fun upsertFieldTest(item: FieldTestEntity) { tests.value = listOf(item) + tests.value.filterNot { it.id == item.id } }
    override fun observeFieldTests(limit: Int): Flow<List<FieldTestEntity>> = tests.map { it.take(limit) }
    override suspend fun upsertPositions(items: List<PositionEntity>) { positions.value = (items + positions.value).distinctBy { it.key } }
    override fun observePositions(nodeId: String?, limit: Int): Flow<List<PositionEntity>> = positions.map { list -> list.filter { nodeId == null || it.nodeId == nodeId }.take(limit) }
    override suspend fun upsertTrustedDevice(item: TrustedDeviceEntity) { trusted = item }
    override suspend fun latestTrustedDevice(): TrustedDeviceEntity? = trusted
    override suspend fun clearTrustedDevices() { trusted = null }
    override suspend fun deleteEventsBefore(cutoff: Long) { events.value = events.value.filter { it.timestampEpochMs >= cutoff } }
    override suspend fun deletePositionsBefore(cutoff: Long) { positions.value = positions.value.filter { it.timestampEpochMs >= cutoff } }
    override suspend fun clearEvents() { historyClearedAtLeastOnce = true; events.value = emptyList() }
    override suspend fun clearMessages() { messages.value = emptyList() }
    override suspend fun clearKnownNodes() = Unit
    override suspend fun clearFieldTests() { tests.value = emptyList() }
    override suspend fun clearPositions() { positions.value = emptyList() }
}
