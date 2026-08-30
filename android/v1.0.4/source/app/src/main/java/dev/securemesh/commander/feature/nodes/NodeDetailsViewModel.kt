package dev.securemesh.commander.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NodeDetailsUiState(
    val node: MeshNode? = null,
    val contact: ContactProfile? = null,
    val links: List<MeshLink> = emptyList(),
    val routes: List<MeshRoute> = emptyList(),
    val events: List<MeshEvent> = emptyList(),
)

private data class NodeContactSnapshot(
    val nodes: List<MeshNode>,
    val contacts: Map<NodeId, ContactProfile>,
)

class NodeDetailsViewModel(
    private val repository: SecureMeshRepository,
    private val nodeId: String,
) : ViewModel() {
    private val nodeContacts = combine(repository.nodes, repository.contactProfiles) { nodes, contacts ->
        NodeContactSnapshot(nodes, contacts)
    }

    val uiState = combine(
        nodeContacts,
        repository.topology,
        repository.routes,
        repository.observeEvents(),
        repository.session,
    ) { snapshot, topology, routes, events, session ->
        val visibleNodes = UiAccessPolicy.visibleNodes(session, snapshot.nodes)
        val visibleTopology = UiAccessPolicy.visibleTopology(session, topology)
        val visibleRoutes = UiAccessPolicy.visibleRoutes(session, routes)
        val visibleEvents = UiAccessPolicy.visibleEvents(session, events)
        NodeDetailsUiState(
            node = visibleNodes.firstOrNull { it.id == nodeId },
            contact = snapshot.contacts[nodeId],
            links = visibleTopology.links.filter { it.fromNode == nodeId || it.toNode == nodeId },
            routes = visibleRoutes.filter { it.destination == nodeId || it.nextHop == nodeId },
            events = visibleEvents.filter { it.nodeId == nodeId }.take(30),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeDetailsUiState())

    fun saveContact(alias: String?, note: String?, pinned: Boolean) {
        viewModelScope.launch { repository.updateContactProfile(nodeId, alias, note, pinned) }
    }

    fun clearContact() {
        viewModelScope.launch { repository.clearContactProfile(nodeId) }
    }
}
