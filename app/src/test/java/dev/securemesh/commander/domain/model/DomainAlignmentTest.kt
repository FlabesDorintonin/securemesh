package dev.securemesh.commander.domain.model

import dev.securemesh.commander.domain.service.UiAccessPolicy
import org.junit.Assert.*
import org.junit.Test

class DomainAlignmentTest {
    private fun identity(
        id: String = "SM-LOCAL-42",
        role: NodeRole = NodeRole.MEMBER,
        capabilities: Set<DeviceCapability> = emptySet(),
    ) = NodeIdentity(id, "Local", role, "0.5", 1, capabilities)

    private fun session(
        identity: NodeIdentity,
        permissions: Set<SessionPermission> = emptySet(),
        auth: AuthenticationState = AuthenticationState.AUTHENTICATED,
    ) = SecureMeshSession(
        localNodeIdentity = identity,
        connectionState = SecureSessionConnectionState.SECURE_SESSION_ESTABLISHED,
        authenticationState = auth,
        grantedPermissions = permissions,
        connectedSinceEpochMs = 123L,
    )

    @Test fun `role is not permission`() {
        val commander = session(identity(role = NodeRole.COMMANDER))
        assertFalse(commander.can(SessionPermission.MANAGE_NETWORK))
        assertFalse(UiAccessPolicy.canShowTopology(commander))
    }

    @Test fun `team position permission does not require full node list permission`() {
        val local = identity(capabilities = setOf(DeviceCapability.GPS))
        val session = session(local, setOf(SessionPermission.VIEW_TEAM_POSITIONS))
        val nodes = listOf(
            MeshNode(local, true, 10L),
            MeshNode(identity("SM-REMOTE"), true, 10L),
        )
        assertTrue(UiAccessPolicy.visibleNodes(session, nodes).isEmpty())
        assertEquals(listOf("SM-REMOTE"), UiAccessPolicy.visiblePositionNodes(session, nodes).map { it.id })
    }

    @Test fun `capability and permission are both required for gated UI`() {
        val id = identity(capabilities = setOf(DeviceCapability.GPS))
        val noPermission = session(id)
        val ownOnly = session(id, setOf(SessionPermission.VIEW_OWN_POSITION))
        assertFalse(UiAccessPolicy.canShowMap(noPermission))
        assertTrue(UiAccessPolicy.canShowMap(ownOnly))
        assertTrue(UiAccessPolicy.canViewPosition(ownOnly, id.nodeId))
        assertFalse(UiAccessPolicy.canViewPosition(ownOnly, "SM-REMOTE"))
    }

    @Test fun `unauthenticated session cannot exercise advertised permissions`() {
        val id = identity(capabilities = setOf(DeviceCapability.MESSAGING))
        val unauthenticated = session(id, setOf(SessionPermission.SEND_MESSAGE), AuthenticationState.NOT_AUTHENTICATED)
        assertFalse(unauthenticated.can(SessionPermission.SEND_MESSAGE))
        assertFalse(UiAccessPolicy.canSendMessages(unauthenticated))
    }

    @Test fun `local node identity is SecureMesh id not BLE address`() {
        val secureIdentity = identity(id = "SM-7C21")
        val s = session(secureIdentity, setOf(SessionPermission.VIEW_OWN_NODE))
        val transportDevice = DiscoveredDevice(
            address = "AA:BB:CC:DD:EE:FF",
            advertisedName = "SecureMesh",
            rssi = -50,
            lastSeenEpochMs = 1,
            classification = DeviceClassification.SECUREMESH_CANDIDATE,
            bondStatus = BondStatus.NOT_BONDED,
        )
        assertEquals("SM-7C21", s.localNodeIdentity.nodeId)
        assertNotEquals(transportDevice.address, s.localNodeIdentity.nodeId)
        assertNull(transportDevice.secureMeshNodeId)
    }

    @Test fun `directional link metrics can differ between same pair`() {
        val node = MeshNode(identity(), true, 1L, null, null, null, null)
        val forward = MeshLink(node.id, "SM-REMOTE", -96, -2.0, .81, 3, 10L)
        val reverse = MeshLink("SM-REMOTE", node.id, -67, 8.0, .99, 0, 11L)
        assertNotEquals(forward.rssi, reverse.rssi)
        assertNotEquals(forward.snr, reverse.snr)
        assertEquals(LinkQuality.DEGRADED, forward.quality())
        assertEquals(LinkQuality.EXCELLENT, reverse.quality())
    }

    @Test fun `topology contains network identities not screen coordinates`() {
        val topology = MeshTopology(
            nodes = listOf("SM-LOCAL", "SM-REMOTE"),
            links = listOf(MeshLink("SM-LOCAL","SM-REMOTE",null,null,null,null,null)),
            updatedAtEpochMs = 1L,
        )
        assertEquals(listOf("SM-LOCAL", "SM-REMOTE"), topology.nodes)
    }

    @Test fun `ui projection hides remote data without explicit permissions`() {
        val localIdentity = identity(id = "SM-LOCAL")
        val s = session(localIdentity, setOf(SessionPermission.VIEW_OWN_NODE))
        val local = MeshNode(localIdentity, true, 1L, null, null, null, null)
        val remoteIdentity = identity(id = "SM-REMOTE")
        val remote = MeshNode(remoteIdentity, true, 1L, null, null, null, null)
        assertEquals(listOf("SM-LOCAL"), UiAccessPolicy.visibleNodes(s, listOf(local, remote)).map { it.id })
        assertTrue(UiAccessPolicy.visibleEvents(s, listOf(MeshEvent("E", 1, EventCategory.SYSTEM, "x", "y"))).isEmpty())
        assertTrue(UiAccessPolicy.visibleMessages(s, listOf(MeshMessage("M", "SM-LOCAL", "SM-REMOTE", "x", 1, progressState = MessageDeliveryState.QUEUED))).isEmpty())
    }

    @Test fun `conversation foundation distinguishes channel types`() {
        assertEquals(
            setOf(ConversationType.DIRECT, ConversationType.GROUP, ConversationType.TEAM, ConversationType.SYSTEM, ConversationType.COMMAND),
            ConversationType.entries.toSet(),
        )
    }

    @Test fun `trusted device metadata is keyed by SecureMesh identity`() {
        val trusted = TrustedDeviceMetadata("SM-7C21", "Field Node", 100L, 1)
        assertEquals("SM-7C21", trusted.nodeId)
    }
}
