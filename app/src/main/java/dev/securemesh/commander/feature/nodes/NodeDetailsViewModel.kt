package dev.securemesh.commander.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*

data class NodeDetailsUiState(
    val node: MeshNode? = null,
    val links: List<MeshLink> = emptyList(),
    val routes: List<MeshRoute> = emptyList(),
    val events: List<MeshEvent> = emptyList(),
)

class NodeDetailsViewModel(repository: SecureMeshRepository, nodeId: String) : ViewModel() {
    val uiState = combine(
        repository.nodes,
        repository.topology,
        repository.routes,
        repository.observeEvents(),
        repository.session,
    ) { nodes, topology, routes, events, session ->
        val visibleNodes = UiAccessPolicy.visibleNodes(session, nodes)
        val visibleTopology = UiAccessPolicy.visibleTopology(session, topology)
        val visibleRoutes = UiAccessPolicy.visibleRoutes(session, routes)
        val visibleEvents = UiAccessPolicy.visibleEvents(session, events)
        NodeDetailsUiState(
            node = visibleNodes.firstOrNull { it.id == nodeId },
            links = visibleTopology.links.filter { it.fromNode == nodeId || it.toNode == nodeId },
            routes = visibleRoutes.filter { it.destination == nodeId || it.nextHop == nodeId },
            events = visibleEvents.filter { it.nodeId == nodeId }.take(30),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeDetailsUiState())
}
