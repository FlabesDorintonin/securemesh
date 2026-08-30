package dev.securemesh.commander.data.mock

import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MockTransportTest {
    @Test fun `current v05 demo is firmware honest and hop ack is not e2e delivery`() = runBlocking {
        val transport = MockTransport()
        transport.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            val session = requireNotNull(transport.session.value)
            val local = session.localNodeIdentity.nodeId
            assertEquals(DemoProfile.CURRENT_FIRMWARE_V05, transport.demoProfile.value)
            assertEquals(AuthenticationState.AUTHENTICATED, session.authenticationState)
            assertTrue(transport.nodes.value.none { it.position != null })
            assertTrue(transport.nodes.value.all { it.batteryPercent == null && it.voltage == null && it.uptimeSec == null })
            assertTrue(transport.topology.value.links.all { it.pdr == null && it.retries == null })
            assertTrue(transport.routes.value.all { it.type == RouteType.DIRECT || it.type == RouteType.STATIC })
            assertTrue(transport.routes.value.all { it.quality == null && it.hopCount == null && it.updatedAtEpochMs == null })

            val routedTarget = transport.routes.value.first { it.type == RouteType.STATIC }.destination
            val id = transport.sendMessage(routedTarget, "test").getOrThrow()
            delay(1_200)
            val message = transport.messages.value.first { it.id == id }
            assertEquals(local, message.origin)
            assertEquals(MessageDeliveryState.FINAL_CONFIRMATION_PENDING, message.progressState)
            assertEquals(MessageFinalState.UNKNOWN, message.finalState)
            assertTrue(message.hopTrace.isNotEmpty())
            assertTrue(message.hopTrace.all { it.ackState == HopAckState.ACKED })
            assertNull(message.deliveredAtEpochMs)
        } finally {
            transport.stop()
        }
    }

    @Test fun `directional metrics are modeled separately`() = runBlocking {
        val transport = MockTransport()
        transport.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            val links = transport.topology.value.links
            val pair = links.firstNotNullOf { forward ->
                links.firstOrNull { it.fromNode == forward.toNode && it.toNode == forward.fromNode }
                    ?.let { reverse -> forward to reverse }
            }
            assertNotEquals(pair.first.rssi, pair.second.rssi)
            assertNotEquals(pair.first.snr, pair.second.snr)
        } finally { transport.stop() }
    }

    @Test fun `relay lost makes routed current firmware message fail on hop`() = runBlocking {
        val transport = MockTransport()
        transport.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            val target = transport.routes.value.first { it.type == RouteType.STATIC }.destination
            transport.applyScenario("RELAY LOST")
            val id = transport.sendMessage(target, "test").getOrThrow()
            delay(1_000)
            val message = transport.messages.value.first { it.id == id }
            assertEquals(MessageDeliveryState.FAILED, message.progressState)
            assertEquals(MessageFinalState.FAILED, message.finalState)
            assertTrue(message.hopTrace.any { it.ackState == HopAckState.TIMEOUT })
        } finally { transport.stop() }
    }

    @Test fun `future demo explicitly enables richer capabilities and synthetic e2e confirmation`() = runBlocking {
        val transport = MockTransport()
        transport.launchDemo(DemoProfile.FUTURE_DEMO)
        try {
            val session = requireNotNull(transport.session.value)
            assertEquals(DemoProfile.FUTURE_DEMO, transport.demoProfile.value)
            assertTrue(session.supports(DeviceCapability.GPS))
            assertTrue(session.can(SessionPermission.VIEW_TEAM_POSITIONS))
            assertTrue(transport.nodes.value.any { it.position != null })
            assertTrue(transport.routes.value.any { it.type == RouteType.DYNAMIC })
            val target = transport.routes.value.first { it.type == RouteType.DYNAMIC }.destination
            val id = transport.sendMessage(target, "future").getOrThrow()
            delay(1_300)
            val message = transport.messages.value.first { it.id == id }
            assertEquals(MessageDeliveryState.DELIVERED, message.progressState)
            assertEquals(MessageFinalState.DELIVERED, message.finalState)
            assertNotNull(message.deliveredAtEpochMs)
        } finally { transport.stop() }
    }


    @Test fun `mock scan is bounded and unknown BLE never becomes an authenticated SecureMesh session`() = runBlocking {
        val transport = MockTransport(now = { 1_000L })
        transport.start()
        try {
            transport.startScan(10)
            val found = transport.connectionState.value as MeshConnectionState.DeviceFound
            val unknown = transport.discoveredDevices.value.first { it.classification == DeviceClassification.UNKNOWN_BLE }
            assertEquals(6_000L, found.scanEndsAtEpochMs)
            transport.connect(unknown)
            val connected = transport.connectionState.value as MeshConnectionState.Connected
            assertFalse(connected.protocolConfigured)
            assertEquals(SecureSessionState.NOT_CONFIGURED, connected.secureSession)
            assertNull(transport.session.value)
        } finally { transport.stop() }
    }

    @Test fun `offline relay last seen stops advancing`() = runBlocking {
        var clock = 1_000L
        val transport = MockTransport(now = { clock })
        transport.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            transport.applyScenario("RELAY LOST")
            val relayId = transport.routes.value.first { it.type == RouteType.DIRECT }.destination
            val first = transport.nodes.value.first { it.id == relayId }.lastSeenEpochMs
            clock = 9_000L
            delay(1_100)
            val second = transport.nodes.value.first { it.id == relayId }.lastSeenEpochMs
            assertEquals(first, second)
        } finally { transport.stop() }
    }

    @Test fun `field test source must be local node and current v05 pdr remains unavailable`() = runBlocking {
        val transport = MockTransport()
        transport.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05)
        try {
            val local = requireNotNull(transport.session.value).localNodeIdentity.nodeId
            val target = transport.routes.value.first { it.destination != local }.destination
            val wrong = transport.startFieldTest(FieldTestConfig("NOT-LOCAL", target, FieldTestMode.AUTO, 1, 50, 32))
            assertTrue(wrong.isFailure)
            val ok = transport.startFieldTest(FieldTestConfig(local, target, FieldTestMode.AUTO, 2, 50, 32))
            assertTrue(ok.isSuccess)
            delay(250)
            val test = requireNotNull(transport.activeFieldTest.value)
            assertNull(test.confirmedReceived)
            assertNull(test.confirmedLost)
            assertNull(test.pdr)
            assertTrue(test.points.flatMap { it.hopResults }.isNotEmpty())
        } finally { transport.stop() }
    }
}
