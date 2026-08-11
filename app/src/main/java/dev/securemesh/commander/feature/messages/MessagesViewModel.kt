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
)

class MessagesViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    val uiState = combine(repository.messages, repository.nodes, repository.session) { messages, nodes, session ->
        MessagesUiState(
            messages = UiAccessPolicy.visibleMessages(session, messages),
            nodes = UiAccessPolicy.visibleNodes(session, nodes),
            localNodeId = session?.localNodeIdentity?.nodeId,
            canView = UiAccessPolicy.canShowMessages(session),
            canSend = UiAccessPolicy.canSendMessages(session),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun send(destination: String, text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        if (!uiState.value.canSend) { _error.value = "SEND_MESSAGE not granted"; return@launch }
        repository.sendMessage(destination, text.trim()).onFailure { _error.value = it.message }
    }
}
