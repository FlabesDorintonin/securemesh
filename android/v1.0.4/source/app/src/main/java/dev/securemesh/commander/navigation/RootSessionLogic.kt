package dev.securemesh.commander.navigation

import dev.securemesh.commander.domain.model.AuthenticationState
import dev.securemesh.commander.domain.model.MeshConnectionState
import dev.securemesh.commander.domain.model.SecureMeshSession
import dev.securemesh.commander.domain.model.TransportMode

/** Keep the main shell through expected reconnect/handshake transitions, but leave it after a terminal BLE session loss. */
fun shouldExitMainShell(mode: TransportMode, session: SecureMeshSession?, connection: MeshConnectionState): Boolean {
    if (mode != TransportMode.BLE) return false
    if (session?.authenticationState == AuthenticationState.AUTHENTICATED) return false
    return when (connection) {
        is MeshConnectionState.Reconnecting,
        is MeshConnectionState.Connecting,
        is MeshConnectionState.PairingRequired,
        is MeshConnectionState.Authenticating,
        is MeshConnectionState.DiscoveringServices,
        is MeshConnectionState.IdentifyingSecureMesh,
        is MeshConnectionState.SyncingSession -> false
        else -> true
    }
}
