package dev.securemesh.commander.navigation

import dev.securemesh.commander.domain.model.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSessionLogicTest {
    private val identity = NodeIdentity("A", "A", NodeRole.MEMBER, null, 1, emptySet())
    private val session = SecureMeshSession(
        identity,
        SecureSessionConnectionState.SECURE_SESSION_ESTABLISHED,
        AuthenticationState.AUTHENTICATED,
        emptySet(),
        0L,
    )

    @Test fun `authenticated BLE session stays in main shell`() {
        val device = DiscoveredDevice("AA", null, -40, 0L, DeviceClassification.TRUSTED_SECUREMESH, BondStatus.BONDED)
        val connection = MeshConnectionState.Connected(device, secureSession = SecureSessionState.ESTABLISHED, protocolConfigured = true)
        assertFalse(shouldExitMainShell(TransportMode.BLE, session, connection))
    }

    @Test fun `reconnect transition keeps main shell alive`() {
        assertFalse(shouldExitMainShell(TransportMode.BLE, null, MeshConnectionState.Reconnecting("A", 1, 0L)))
    }

    @Test fun `terminal disconnect leaves main shell`() {
        assertTrue(shouldExitMainShell(TransportMode.BLE, null, MeshConnectionState.Disconnected("lost")))
    }

    @Test fun `mock lifecycle never forces production navigation`() {
        assertFalse(shouldExitMainShell(TransportMode.MOCK, null, MeshConnectionState.Idle))
    }
}
