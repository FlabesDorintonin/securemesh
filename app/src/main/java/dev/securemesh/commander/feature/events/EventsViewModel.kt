package dev.securemesh.commander.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.EventCategory
import dev.securemesh.commander.domain.model.MeshEvent
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*

data class EventsUiState(
    val items: List<MeshEvent> = emptyList(),
    val query: String = "",
    val category: EventCategory? = null,
    val allowed: Boolean = false,
)

class EventsViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<EventCategory?>(null)
    val uiState = combine(repository.observeEvents(), repository.session, query, category) { events, session, q, c ->
        val allowed = UiAccessPolicy.canShowSystemLog(session)
        val visible = UiAccessPolicy.visibleEvents(session, events)
        val text = q.trim().lowercase()
        EventsUiState(
            items = visible.filter { (c == null || it.category == c) && (text.isBlank() || it.title.lowercase().contains(text) || it.details.lowercase().contains(text) || it.nodeId.orEmpty().lowercase().contains(text)) },
            query = q,
            category = c,
            allowed = allowed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsUiState())
    fun query(value: String) { query.value = value }
    fun category(value: EventCategory?) { category.value = value }
    suspend fun exportCsv(): String = if (uiState.value.allowed) repository.exportEventsCsv() else ""
    suspend fun exportJson(): String = if (uiState.value.allowed) repository.exportEventsJson() else "[]"
}
