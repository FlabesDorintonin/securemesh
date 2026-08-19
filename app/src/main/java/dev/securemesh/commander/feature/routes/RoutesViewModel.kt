package dev.securemesh.commander.feature.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RoutesUiState(
    val routes: List<MeshRoute> = emptyList(),
    val nodes: List<MeshNode> = emptyList(),
    val canView: Boolean = false,
    val canManage: Boolean = false,
    val busy: Boolean = false,
)

class RoutesViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val uiState = combine(
        combine(repository.routes, repository.nodes, repository.session) { routes, nodes, session -> Triple(routes, nodes, session) },
        busy,
    ) { core, isBusy ->
        val (routes, nodes, session) = core
        RoutesUiState(
            UiAccessPolicy.visibleRoutes(session, routes),
            UiAccessPolicy.visibleNodes(session, nodes),
            UiAccessPolicy.canShowRoutes(session),
            UiAccessPolicy.canManageRoutes(session),
            isBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutesUiState())

    fun add(destination: String, via: String, onSuccess: () -> Unit = {}) = execute(onSuccess) {
        repository.addStaticRoute(destination, via)
    }

    fun remove(destination: String, onSuccess: () -> Unit = {}) = execute(onSuccess) { repository.removeRoute(destination) }

    fun clearError() { _error.value = null }

    private fun execute(onSuccess: () -> Unit = {}, block: suspend () -> Result<Unit>) {
        if (busy.value) return
        viewModelScope.launch {
            if (!uiState.value.canManage) { _error.value = "MANAGE_ROUTES not granted"; return@launch }
            busy.value = true
            _error.value = null
            try {
                block().onSuccess { onSuccess() }.onFailure { _error.value = it.message ?: "Команда маршрутизации не выполнена" }
            } finally {
                busy.value = false
            }
        }
    }
}
