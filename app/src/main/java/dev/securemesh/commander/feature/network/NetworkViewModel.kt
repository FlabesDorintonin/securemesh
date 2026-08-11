package dev.securemesh.commander.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*

data class NetworkUiState(
    val topology: MeshTopology = MeshTopology(emptyList(), emptyList(), 0),
    val nodes: List<MeshNode> = emptyList(),
    val mapNodes: List<MeshNode> = emptyList(),
    val localNodeId: NodeId? = null,
    val canTopology: Boolean = false,
    val canMap: Boolean = false,
)

class NetworkViewModel(repository: SecureMeshRepository) : ViewModel() {
    val uiState = combine(repository.topology, repository.nodes, repository.session) { topology, nodes, session ->
        val visibleNodes = UiAccessPolicy.visibleNodes(session, nodes)
        NetworkUiState(
            topology = UiAccessPolicy.visibleTopology(session, topology),
            nodes = visibleNodes,
            mapNodes = UiAccessPolicy.visiblePositionNodes(session, nodes),
            localNodeId = session?.localNodeIdentity?.nodeId,
            canTopology = UiAccessPolicy.canShowTopology(session),
            canMap = UiAccessPolicy.canShowMap(session),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState())
}
