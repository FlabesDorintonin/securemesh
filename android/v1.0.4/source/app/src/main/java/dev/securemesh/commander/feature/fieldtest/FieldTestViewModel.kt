package dev.securemesh.commander.feature.fieldtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FieldTestUiState(
    val nodes: List<MeshNode> = emptyList(),
    val localNodeId: NodeId? = null,
    val canRun: Boolean = false,
    val active: FieldTestSession? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class FieldTestViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState = combine(
        combine(repository.nodes, repository.session, repository.activeFieldTest) { nodes, session, active -> Triple(nodes, session, active) },
        busy,
        error,
    ) { core, isBusy, failure ->
        val (nodes, session, active) = core
        val allowed = UiAccessPolicy.canRunFieldTest(session)
        FieldTestUiState(
            nodes = UiAccessPolicy.visibleNodes(session, nodes),
            localNodeId = session?.localNodeIdentity?.nodeId,
            canRun = allowed,
            active = active.takeIf { allowed },
            busy = isBusy,
            error = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FieldTestUiState())

    val history = combine(repository.observeFieldTestHistory(), repository.session) { history, session ->
        if (!UiAccessPolicy.canRunFieldTest(session)) emptyList()
        else history.filter { it.config.source == session?.localNodeIdentity?.nodeId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start(config: FieldTestConfig) {
        if (busy.value || !uiState.value.canRun) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                repository.startFieldTest(config).onFailure { error.value = it.message ?: "Не удалось запустить полевой тест" }
            } finally {
                busy.value = false
            }
        }
    }

    fun stop() {
        if (busy.value || !uiState.value.canRun) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                repository.stopFieldTest().onFailure { failure ->
                    error.value = failure.message ?: "Не удалось остановить полевой тест"
                }
            } catch (t: Throwable) {
                error.value = t.message ?: "Не удалось остановить полевой тест"
            } finally {
                busy.value = false
            }
        }
    }

    fun clearError() { error.value = null }
    suspend fun exportCsv() = if (uiState.value.canRun) repository.exportFieldTestsCsv() else ""
    suspend fun exportJson() = if (uiState.value.canRun) repository.exportFieldTestsJson() else "[]"
}
