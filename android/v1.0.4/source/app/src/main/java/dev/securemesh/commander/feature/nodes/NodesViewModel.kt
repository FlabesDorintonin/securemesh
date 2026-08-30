package dev.securemesh.commander.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*

enum class NodeSort { NAME, RSSI, BATTERY, LAST_SEEN }
data class NodeFilters(
    val onlineOnly: Boolean = false,
    val offlineOnly: Boolean = false,
    val relay: Boolean = false,
    val commander: Boolean = false,
    val gpsLost: Boolean = false,
    val weakLink: Boolean = false,
)
data class NodeListItem(val node: MeshNode, val primaryLink: MeshLink?, val contact: ContactProfile? = null)
data class NodesUiState(
    val nodes: List<NodeListItem> = emptyList(),
    val query: String = "",
    val filters: NodeFilters = NodeFilters(),
    val sort: NodeSort = NodeSort.NAME,
)

private data class NodeListControls(val query: String, val filters: NodeFilters, val sort: NodeSort)

class NodesViewModel(repository: SecureMeshRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow(NodeFilters())
    private val sort = MutableStateFlow(NodeSort.NAME)

    // Keep the public combine below within typed overloads. Coroutines' 6+ flow combine uses an Array transform.
    private val controls = combine(query, filters, sort) { q, f, s -> NodeListControls(q, f, s) }

    val uiState = combine(repository.nodes, repository.topology, repository.session, repository.contactProfiles, controls) { nodes, topology, session, contacts, controls ->
        val visibleNodes = UiAccessPolicy.visibleNodes(session, nodes)
        val visibleTopology = UiAccessPolicy.visibleTopology(session, topology)
        fun primaryLink(id: NodeId): MeshLink? = visibleTopology.links
            .filter { it.fromNode == id || it.toNode == id }
            .maxByOrNull { it.rssi ?: Int.MIN_VALUE }

        val text = controls.query.trim().lowercase()
        var sequence = visibleNodes.asSequence()
            .map { NodeListItem(it, primaryLink(it.id), contacts[it.id]) }
            .filter { item ->
                val alias = item.contact?.alias.orEmpty()
                text.isBlank() || item.node.id.lowercase().contains(text) || item.node.name.lowercase().contains(text) || alias.lowercase().contains(text)
            }

        val f = controls.filters
        if (f.onlineOnly) sequence = sequence.filter { it.node.online }
        if (f.offlineOnly) sequence = sequence.filter { !it.node.online }
        if (f.relay) sequence = sequence.filter { it.node.role == NodeRole.RELAY }
        if (f.commander) sequence = sequence.filter { it.node.role == NodeRole.COMMANDER }
        if (f.gpsLost) sequence = sequence.filter { it.node.position?.status(System.currentTimeMillis()) != GpsStatus.FIX }
        if (f.weakLink) sequence = sequence.filter { it.primaryLink?.quality() in setOf(LinkQuality.DEGRADED, LinkQuality.CRITICAL) }

        val list = when (controls.sort) {
            NodeSort.NAME -> sequence.sortedBy { it.contact?.displayName(it.node.name) ?: it.node.name }
            NodeSort.RSSI -> sequence.sortedByDescending { it.primaryLink?.rssi ?: Int.MIN_VALUE }
            NodeSort.BATTERY -> sequence.sortedByDescending { it.node.batteryPercent ?: -1 }
            NodeSort.LAST_SEEN -> sequence.sortedByDescending { it.node.lastSeenEpochMs }
        }.toList()
        NodesUiState(list, controls.query, controls.filters, controls.sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodesUiState())

    fun query(value: String) { query.value = value }
    fun filters(value: NodeFilters) { filters.value = value }
    fun sort(value: NodeSort) { sort.value = value }
}
