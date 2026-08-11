package dev.securemesh.commander.domain.service

import dev.securemesh.commander.domain.model.*

/**
 * UI projection only. This class decides what the app offers, not what the network authorizes.
 * Firmware remains the security authority for every privileged command.
 */
object UiAccessPolicy {
    fun canShowMessages(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.MESSAGING) == true && session.can(SessionPermission.VIEW_MESSAGES)

    fun canSendMessages(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.MESSAGING) == true && session.can(SessionPermission.SEND_MESSAGE)

    fun canShowNodes(session: SecureMeshSession?): Boolean = session?.can(SessionPermission.VIEW_NODES) == true

    fun canShowTopology(session: SecureMeshSession?): Boolean =
        session?.can(SessionPermission.VIEW_NETWORK_TOPOLOGY) == true

    fun canShowRoutes(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.ROUTING) == true && session.can(SessionPermission.VIEW_ROUTES)

    fun canManageRoutes(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.ROUTING) == true && session.can(SessionPermission.MANAGE_ROUTES)

    fun canRunFieldTest(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.FIELD_TEST) == true && session.can(SessionPermission.RUN_FIELD_TEST)

    fun canShowSystemLog(session: SecureMeshSession?): Boolean = session?.can(SessionPermission.VIEW_SYSTEM_LOG) == true

    fun canShowSos(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.SOS) == true && session.can(SessionPermission.VIEW_SOS)

    fun canShowDiagnostics(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.NETWORK_DIAGNOSTICS) == true && session.can(SessionPermission.VIEW_NETWORK_DIAGNOSTICS)

    fun canShowMap(session: SecureMeshSession?): Boolean =
        session?.supports(DeviceCapability.GPS) == true &&
            (session.can(SessionPermission.VIEW_OWN_POSITION) || session.can(SessionPermission.VIEW_TEAM_POSITIONS))

    /** Projection helpers prevent screens/search from accidentally exposing data that the session did not grant.
     * Transport/firmware must still enforce the same or stricter rules; this is defense-in-depth for presentation. */
    fun visibleNodes(session: SecureMeshSession?, nodes: List<MeshNode>): List<MeshNode> {
        session ?: return emptyList()
        if (canShowNodes(session)) return nodes
        if (!session.can(SessionPermission.VIEW_OWN_NODE)) return emptyList()
        return nodes.filter { it.id == session.localNodeIdentity.nodeId }
    }

    fun visibleTopology(session: SecureMeshSession?, topology: MeshTopology): MeshTopology {
        session ?: return MeshTopology(emptyList(), emptyList(), topology.updatedAtEpochMs)
        if (canShowTopology(session)) return topology
        val local = session.localNodeIdentity.nodeId
        return MeshTopology(
            nodes = if (session.can(SessionPermission.VIEW_OWN_NODE)) listOf(local) else emptyList(),
            links = emptyList(),
            updatedAtEpochMs = topology.updatedAtEpochMs,
        )
    }

    fun visibleMessages(session: SecureMeshSession?, messages: List<MeshMessage>): List<MeshMessage> =
        if (canShowMessages(session)) messages else emptyList()

    fun visibleRoutes(session: SecureMeshSession?, routes: List<MeshRoute>): List<MeshRoute> =
        if (canShowRoutes(session)) routes else emptyList()

    fun visibleEvents(session: SecureMeshSession?, events: List<MeshEvent>): List<MeshEvent> =
        if (canShowSystemLog(session)) events else emptyList()

    /** Position visibility is intentionally independent from VIEW_NODES. A session may be allowed to
     * render authorized team markers without receiving the full node-management/list surface. */
    fun visiblePositionNodes(session: SecureMeshSession?, nodes: List<MeshNode>): List<MeshNode> =
        nodes.filter { canViewPosition(session, it.id) }

    fun canViewPosition(session: SecureMeshSession?, nodeId: NodeId): Boolean {
        session ?: return false
        if (!session.supports(DeviceCapability.GPS)) return false
        return if (nodeId == session.localNodeIdentity.nodeId) {
            session.can(SessionPermission.VIEW_OWN_POSITION)
        } else {
            session.can(SessionPermission.VIEW_TEAM_POSITIONS)
        }
    }
}
