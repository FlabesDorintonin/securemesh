package dev.securemesh.commander.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NetworkUiState(
    val topology: MeshTopology = MeshTopology(emptyList(), emptyList(), 0),
    val nodes: List<MeshNode> = emptyList(),
    val mapNodes: List<MeshNode> = emptyList(),
    val positionHistory: List<NodePosition> = emptyList(),
    val activeSos: SosAlert? = null,
    val localNodeId: NodeId? = null,
    val contactProfiles: Map<NodeId, ContactProfile> = emptyMap(),
    val canTopology: Boolean = false,
    val canMap: Boolean = false,
    val canSendMessages: Boolean = false,
    val canSendCommands: Boolean = false,
    val canRaiseSos: Boolean = false,
)

class NetworkViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private data class NetworkInputs(
        val topology: MeshTopology,
        val nodes: List<MeshNode>,
        val sos: SosAlert?,
        val history: List<NodePosition>,
        val contacts: Map<NodeId, ContactProfile>,
    )

    private val inputs = combine(
        repository.topology,
        repository.nodes,
        repository.activeSos,
        repository.observePositionHistory(),
        repository.contactProfiles,
    ) { topology, nodes, sos, history, contacts -> NetworkInputs(topology, nodes, sos, history, contacts) }

    val uiState = combine(inputs, repository.session) { input, session ->
        val visibleNodes = UiAccessPolicy.visibleNodes(session, input.nodes)
        val mapNodes = UiAccessPolicy.visiblePositionNodes(session, input.nodes)
        val visibleIds = mapNodes.mapTo(hashSetOf()) { it.id }
        NetworkUiState(
            topology = UiAccessPolicy.visibleTopology(session, input.topology),
            nodes = visibleNodes,
            mapNodes = mapNodes,
            positionHistory = input.history.filter { it.nodeId in visibleIds },
            activeSos = input.sos?.takeIf { UiAccessPolicy.canShowSos(session) },
            localNodeId = session?.localNodeIdentity?.nodeId,
            contactProfiles = input.contacts.filterKeys { it in visibleIds || visibleNodes.any { node -> node.id == it } },
            canTopology = UiAccessPolicy.canShowTopology(session),
            canMap = UiAccessPolicy.canShowMap(session),
            canSendMessages = UiAccessPolicy.canSendMessages(session),
            canSendCommands = UiAccessPolicy.canSendMessages(session),
            canRaiseSos = session?.supports(DeviceCapability.SOS) == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState())

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()
    private val _actionBusy = MutableStateFlow(false)
    val actionBusy = _actionBusy.asStateFlow()

    fun clearActionMessage() { _actionMessage.value = null }

    fun raiseSos(type: Int = 0) {
        if (_actionBusy.value) return
        viewModelScope.launch {
            _actionBusy.value = true
            repository.raiseSos(type)
                .onSuccess { _actionMessage.value = "SOS передан в mesh · $it" }
                .onFailure { _actionMessage.value = it.message ?: "Не удалось отправить SOS" }
            _actionBusy.value = false
        }
    }

    fun saveContact(nodeId: NodeId, alias: String?, note: String?, pinned: Boolean) {
        viewModelScope.launch { repository.updateContactProfile(nodeId, alias, note, pinned) }
    }

    fun clearContact(nodeId: NodeId) {
        viewModelScope.launch { repository.clearContactProfile(nodeId) }
    }

    fun sendCommand(nodeId: NodeId, kind: CommandNoticeKind, target: NodePosition? = null) {
        if (_actionBusy.value) return
        viewModelScope.launch {
            _actionBusy.value = true
            repository.sendCommandNotice(nodeId, kind, target)
                .onSuccess { id -> _actionMessage.value = "Команда ${kind.ruTitle()} отправлена · $id" }
                .onFailure { _actionMessage.value = it.message ?: "Команда не отправлена" }
            _actionBusy.value = false
        }
    }
}

private fun CommandNoticeKind.ruTitle(): String = when (this) {
    CommandNoticeKind.RETURN -> "ВЕРНИСЬ"
    CommandNoticeKind.CHECK_IN -> "ДАЙ СТАТУС"
    CommandNoticeKind.HOLD -> "ОСТАВАЙСЯ"
    CommandNoticeKind.MOVE_TO_WAYPOINT -> "К ТОЧКЕ"
}
