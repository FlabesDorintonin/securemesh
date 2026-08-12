package dev.securemesh.commander.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val connection: MeshConnectionState = MeshConnectionState.Idle,
    val session: SecureMeshSession? = null,
    val profile: DemoProfile? = null,
    val mode: TransportMode = TransportMode.BLE,
    val phoneBluetooth: String = "UNKNOWN",
    val blePermission: String = "UNKNOWN",
    val ble: BleDiagnostics? = null,
    val nodes: Int = 0,
    val links: Int = 0,
    val routes: Int = 0,
    val messages: Int = 0,
    val events: List<MeshEvent> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val allowed: Boolean = false,
)

private data class DiagnosticsContext(
    val connection: MeshConnectionState,
    val session: SecureMeshSession?,
    val profile: DemoProfile?,
    val mode: TransportMode,
    val settings: AppSettings,
    val ble: BleDiagnostics? = null,
)

private data class DiagnosticsMesh(
    val nodes: List<MeshNode>,
    val topology: MeshTopology,
    val routes: List<MeshRoute>,
    val messages: List<MeshMessage>,
    val events: List<MeshEvent>,
)

class DiagnosticsViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val baseContext = combine(
        repository.connectionState,
        repository.session,
        repository.demoProfile,
        repository.transportMode,
        repository.settings,
    ) { connection, session, profile, mode, settings ->
        DiagnosticsContext(connection, session, profile, mode, settings)
    }
    private val context = combine(baseContext, repository.bleDiagnostics) { base, ble -> base.copy(ble = ble) }

    private val mesh = combine(
        repository.nodes,
        repository.topology,
        repository.routes,
        repository.messages,
        repository.observeEvents(),
    ) { nodes, topology, routes, messages, events ->
        DiagnosticsMesh(nodes, topology, routes, messages, events)
    }

    val uiState = combine(context, mesh) { context, mesh ->
        val connection = context.connection
        val session = context.session
        val visibleTopology = UiAccessPolicy.visibleTopology(session, mesh.topology)
        val bluetooth = when (connection) {
            MeshConnectionState.BluetoothUnavailable -> "UNAVAILABLE"
            MeshConnectionState.BluetoothDisabled -> "DISABLED"
            is MeshConnectionState.Scanning,
            is MeshConnectionState.DeviceFound,
            is MeshConnectionState.Connecting,
            is MeshConnectionState.PairingRequired,
            is MeshConnectionState.Authenticating,
            is MeshConnectionState.DiscoveringServices,
            is MeshConnectionState.IdentifyingSecureMesh,
            is MeshConnectionState.SyncingSession,
            is MeshConnectionState.Connected -> "ENABLED"
            else -> "UNKNOWN"
        }
        val permission = when (connection) {
            is MeshConnectionState.PermissionRequired -> "REQUIRED"
            is MeshConnectionState.Scanning,
            is MeshConnectionState.DeviceFound,
            is MeshConnectionState.Connecting,
            is MeshConnectionState.PairingRequired,
            is MeshConnectionState.Authenticating,
            is MeshConnectionState.DiscoveringServices,
            is MeshConnectionState.IdentifyingSecureMesh,
            is MeshConnectionState.SyncingSession,
            is MeshConnectionState.Connected -> "GRANTED"
            else -> "UNKNOWN"
        }
        DiagnosticsUiState(
            connection = connection,
            session = session,
            profile = context.profile,
            mode = context.mode,
            phoneBluetooth = bluetooth,
            blePermission = permission,
            ble = context.ble,
            nodes = UiAccessPolicy.visibleNodes(session, mesh.nodes).size,
            links = visibleTopology.links.size,
            routes = UiAccessPolicy.visibleRoutes(session, mesh.routes).size,
            messages = UiAccessPolicy.visibleMessages(session, mesh.messages).size,
            events = UiAccessPolicy.visibleEvents(session, mesh.events),
            settings = context.settings,
            allowed = UiAccessPolicy.canShowDiagnostics(session),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun clearHistory() = viewModelScope.launch { repository.clearLocalHistory() }
    fun scenario(name: String) = viewModelScope.launch { repository.applyDemoScenario(name) }
}
