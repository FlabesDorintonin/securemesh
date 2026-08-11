package dev.securemesh.commander.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.MeshEvent
import dev.securemesh.commander.domain.model.MeshMessage
import dev.securemesh.commander.domain.model.MeshNode
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*

data class SearchResult(val nodes: List<MeshNode> = emptyList(), val messages: List<MeshMessage> = emptyList(), val events: List<MeshEvent> = emptyList())
class SearchViewModel(repository: SecureMeshRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    val result = combine(query, repository.nodes, repository.messages, repository.observeEvents(), repository.session) { q, nodes, messages, events, session ->
        val text = q.trim().lowercase()
        if (text.isBlank()) SearchResult() else SearchResult(
            UiAccessPolicy.visibleNodes(session, nodes).filter { it.id.lowercase().contains(text) || it.name.lowercase().contains(text) }.take(20),
            UiAccessPolicy.visibleMessages(session, messages).filter { it.payload.lowercase().contains(text) || it.id.lowercase().contains(text) }.take(20),
            UiAccessPolicy.visibleEvents(session, events).filter { it.title.lowercase().contains(text) || it.details.lowercase().contains(text) }.take(20),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResult())
    val text = query.asStateFlow()
    fun query(value: String) { query.value = value }
}
