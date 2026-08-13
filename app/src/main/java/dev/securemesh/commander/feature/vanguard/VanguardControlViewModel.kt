package dev.securemesh.commander.feature.vanguard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class VanguardControlUiState(
    val session: SecureMeshSession? = null,
    val knownNodeIds: List<NodeId> = emptyList(),
    val nodes: List<MeshNode> = emptyList(),
    val manifest: VanguardManifest? = null,
    val diagnostics: VanguardDiagnostics? = null,
    val labPolicies: List<LabLinkPolicy> = emptyList(),
    val deviceUi: DeviceUiState? = null,
    val vanguardAvailable: Boolean = false,
    val manifestAvailable: Boolean = false,
    val faultLabAvailable: Boolean = false,
    val uiOsAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

private data class CoreState(
    val session: SecureMeshSession?,
    val known: List<NodeId>,
    val nodes: List<MeshNode>,
    val manifest: VanguardManifest?,
    val diagnostics: VanguardDiagnostics?,
)
private data class AuxState(val policies: List<LabLinkPolicy>, val ui: DeviceUiState?, val busy: Boolean, val error: String?)

class VanguardControlViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val core = combine(
        repository.session,
        repository.knownNodeIds,
        repository.nodes,
        repository.networkManifest,
        repository.vanguardDiagnostics,
    ) { session, known, nodes, manifest, diagnostics -> CoreState(session, known, nodes, manifest, diagnostics) }

    private val aux = combine(repository.labLinkPolicies, repository.deviceUiState, busy, error) { policies, ui, isBusy, failure ->
        AuxState(policies, ui, isBusy, failure)
    }

    val uiState = combine(core, aux) { core, aux ->
        val session = core.session
        VanguardControlUiState(
            session = session,
            knownNodeIds = core.known,
            nodes = core.nodes,
            manifest = core.manifest,
            diagnostics = core.diagnostics,
            labPolicies = aux.policies,
            deviceUi = aux.ui,
            vanguardAvailable = session?.supports(DeviceCapability.VANGUARD) == true,
            manifestAvailable = session?.supports(DeviceCapability.MANIFEST) == true,
            faultLabAvailable = session?.supports(DeviceCapability.FAULT_LAB) == true,
            uiOsAvailable = session?.supports(DeviceCapability.UI_OS) == true,
            busy = aux.busy,
            error = aux.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VanguardControlUiState())

    fun refresh() = executeUnit { repository.refreshVanguardState() }
    fun discover(destination: NodeId) = execute { repository.discoverRoute(destination, true) }
    fun clearRoutes() = execute { repository.clearDynamicRoutes() }
    fun injectBlock(peer: NodeId, durationMs: Long) = execute { repository.injectLinkFailure(peer, durationMs) }
    fun lab(peer: NodeId, preset: LabLinkPreset, durationMs: Long) = execute { repository.setLabLinkPolicy(peer, preset, durationMs) }
    fun setManifest(epoch: Long, nodes: List<NodeId>) = execute { repository.setManifest(epoch, nodes) }
    fun refreshOled() = execute { repository.refreshDeviceUiState() }
    fun oled(action: DeviceUiAction) = execute { repository.sendDeviceUiAction(action) }
    fun clearError() { error.value = null }

    private fun executeUnit(block: suspend () -> Result<Unit>) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try { block().onFailure { error.value = it.message ?: "Команда не выполнена" } }
            finally { busy.value = false }
        }
    }

    private fun execute(block: suspend () -> Result<*>) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try { block().onFailure { error.value = it.message ?: "Команда не выполнена" } }
            finally { busy.value = false }
        }
    }
}
