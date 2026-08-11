package dev.securemesh.commander.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*

data class DashboardUiState(
    val connection: MeshConnectionState = MeshConnectionState.Idle,
    val session: SecureMeshSession? = null,
    val demoProfile: DemoProfile? = null,
    val nodes: List<MeshNode> = emptyList(),
    val topology: MeshTopology = MeshTopology(emptyList(), emptyList(), 0),
    val routes: List<MeshRoute> = emptyList(),
    val messages: List<MeshMessage> = emptyList(),
    val events: List<MeshEvent> = emptyList(),
    val sos: SosAlert? = null,
    val canOpenNodeList: Boolean = false,
    val canOpenEvents: Boolean = false,
)

private data class DashboardNetwork(
    val nodes: List<MeshNode>,
    val topology: MeshTopology,
    val routes: List<MeshRoute>,
)

private data class DashboardActivity(
    val messages: List<MeshMessage>,
    val events: List<MeshEvent>,
    val sos: SosAlert?,
)

class DashboardViewModel(repository: SecureMeshRepository) : ViewModel() {
    private val network = combine(repository.nodes, repository.topology, repository.routes) { nodes, topology, routes ->
        DashboardNetwork(nodes, topology, routes)
    }
    private val activity = combine(repository.messages, repository.observeEvents(), repository.activeSos) { messages, events, sos ->
        DashboardActivity(messages, events, sos)
    }

    val uiState = combine(
        repository.connectionState,
        repository.session,
        repository.demoProfile,
        network,
        activity,
    ) { connection, session, demoProfile, network, activity ->
        DashboardUiState(
            connection = connection,
            session = session,
            demoProfile = demoProfile,
            nodes = UiAccessPolicy.visibleNodes(session, network.nodes),
            topology = UiAccessPolicy.visibleTopology(session, network.topology),
            routes = UiAccessPolicy.visibleRoutes(session, network.routes),
            messages = UiAccessPolicy.visibleMessages(session, activity.messages),
            events = UiAccessPolicy.visibleEvents(session, activity.events),
            sos = activity.sos.takeIf { UiAccessPolicy.canShowSos(session) },
            canOpenNodeList = UiAccessPolicy.canShowNodes(session),
            canOpenEvents = UiAccessPolicy.canShowSystemLog(session),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
