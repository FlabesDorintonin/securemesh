package dev.securemesh.commander.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MessagesUiState(
    val messages: List<MeshMessage> = emptyList(),
    val nodes: List<MeshNode> = emptyList(),
    val localNodeId: NodeId? = null,
    val canView: Boolean = false,
    val canSend: Boolean = false,
    val contactProfiles: Map<NodeId, ContactProfile> = emptyMap(),
)

class MessagesViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private data class NodeDirectory(val nodes: List<MeshNode>, val contacts: Map<NodeId, ContactProfile>)
    private val nodeDirectory = combine(repository.nodes, repository.contactProfiles) { nodes, contacts -> NodeDirectory(nodes, contacts) }

    val uiState = combine(
        repository.observeMessageHistory(),
        nodeDirectory,
        repository.session,
        repository.localHistoryOwnerNodeId,
        repository.connectionState,
    ) { history, directory, session, historyOwner, connectionState ->
        val liveCanView = UiAccessPolicy.canShowMessages(session)
        val localNodeId = session?.localNodeIdentity?.nodeId ?: historyOwner
        val canUseStoredHistory = session == null && historyOwner != null && connectionState.allowsStoredHistory()
        val canView = liveCanView || canUseStoredHistory
        val visibleMessages = if (canView) history else emptyList()

        val visibleLiveNodes = if (session != null) UiAccessPolicy.visibleNodes(session, directory.nodes) else emptyList()
        val existingIds = visibleLiveNodes.mapTo(mutableSetOf()) { it.id }
        val historyPeers = visibleMessages
            .mapNotNull { it.peerFor(localNodeId) }
            .distinct()
            .filterNot(existingIds::contains)
            .map { peerId ->
                val lastSeen = visibleMessages.asSequence()
                    .filter { it.origin == peerId || it.destination == peerId }
                    .maxOfOrNull { it.createdAtEpochMs }
                    ?: 0L
                MeshNode(
                    identity = NodeIdentity(
                        nodeId = peerId,
                        displayName = "Узел $peerId",
                        role = NodeRole.UNKNOWN,
                        firmwareVersion = null,
                        protocolVersion = null,
                        capabilities = setOf(DeviceCapability.MESSAGING),
                    ),
                    online = false,
                    lastSeenEpochMs = lastSeen,
                )
            }

        MessagesUiState(
            messages = visibleMessages,
            nodes = (visibleLiveNodes + historyPeers).distinctBy { it.id },
            localNodeId = localNodeId,
            canView = canView,
            canSend = UiAccessPolicy.canSendMessages(session),
            contactProfiles = directory.contacts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    fun send(destination: String, text: String, onAccepted: () -> Unit = {}) {
        if (_sending.value) return
        viewModelScope.launch {
            val value = text.trim()
            if (value.isBlank()) return@launch
            if (messageUtf8Bytes(value) > SECUREMESH_MESSAGE_MAX_UTF8_BYTES) {
                _error.value = "Сообщение превышает 70 байт UTF-8"
                return@launch
            }
            if (!uiState.value.canSend) { _error.value = "SEND_MESSAGE not granted"; return@launch }
            _sending.value = true
            _error.value = null
            try {
                repository.sendMessage(destination, value)
                    .onSuccess { onAccepted() }
                    .onFailure { _error.value = it.message ?: "Не удалось отправить сообщение" }
            } finally {
                _sending.value = false
            }
        }
    }
}

private fun MeshMessage.peerFor(localNodeId: NodeId?): NodeId? = when {
    localNodeId == null -> null
    origin == localNodeId && destination != localNodeId -> destination
    destination == localNodeId && origin != localNodeId -> origin
    else -> null
}

private fun MeshConnectionState.allowsStoredHistory(): Boolean = when (this) {
    MeshConnectionState.Idle,
    MeshConnectionState.BluetoothUnavailable,
    MeshConnectionState.BluetoothDisabled,
    is MeshConnectionState.PermissionRequired,
    is MeshConnectionState.Disconnected,
    is MeshConnectionState.Error -> true
    else -> false
}
