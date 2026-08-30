package dev.securemesh.commander.data.transport

import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private object NoBleDiagnostics {
    val flow: StateFlow<BleDiagnostics?> = MutableStateFlow<BleDiagnostics?>(null).asStateFlow()
}

private object NoDeviceUiState {
    val flow: StateFlow<DeviceUiState?> = MutableStateFlow<DeviceUiState?>(null).asStateFlow()
}
private object NoOledFramebuffer {
    val flow: StateFlow<OledFramebufferSnapshot?> = MutableStateFlow<OledFramebufferSnapshot?>(null).asStateFlow()
}
private object NoKnownNodes { val flow: StateFlow<List<NodeId>> = MutableStateFlow<List<NodeId>>(emptyList()).asStateFlow() }
private object NoManifest { val flow: StateFlow<VanguardManifest?> = MutableStateFlow<VanguardManifest?>(null).asStateFlow() }
private object NoVanguardDiagnostics { val flow: StateFlow<VanguardDiagnostics?> = MutableStateFlow<VanguardDiagnostics?>(null).asStateFlow() }
private object NoLabPolicies { val flow: StateFlow<List<LabLinkPolicy>> = MutableStateFlow<List<LabLinkPolicy>>(emptyList()).asStateFlow() }

interface MeshTransport {
    val connectionState: StateFlow<MeshConnectionState>
    /** Non-null after SecureMesh identity is known; ESTABLISHED only after authenticated permission sync. */
    val session: StateFlow<SecureMeshSession?>
    val demoProfile: StateFlow<DemoProfile?>
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val nodes: StateFlow<List<MeshNode>>
    val topology: StateFlow<MeshTopology>
    val messages: StateFlow<List<MeshMessage>>
    val routes: StateFlow<List<MeshRoute>>
    val events: StateFlow<List<MeshEvent>>
    val activeFieldTest: StateFlow<FieldTestSession?>
    val activeSos: StateFlow<SosAlert?>
    val bleDiagnostics: StateFlow<BleDiagnostics?> get() = NoBleDiagnostics.flow
    val deviceUiState: StateFlow<DeviceUiState?> get() = NoDeviceUiState.flow
    val oledFramebuffer: StateFlow<OledFramebufferSnapshot?> get() = NoOledFramebuffer.flow
    val knownNodeIds: StateFlow<List<NodeId>> get() = NoKnownNodes.flow
    val networkManifest: StateFlow<VanguardManifest?> get() = NoManifest.flow
    val vanguardDiagnostics: StateFlow<VanguardDiagnostics?> get() = NoVanguardDiagnostics.flow
    val labLinkPolicies: StateFlow<List<LabLinkPolicy>> get() = NoLabPolicies.flow

    suspend fun start()
    suspend fun stop()
    suspend fun startScan(durationMs: Long)
    suspend fun stopScan()
    suspend fun connect(device: DiscoveredDevice)
    suspend fun disconnect()
    suspend fun sendMessage(destination: NodeId, payload: String): Result<MessageId>
    suspend fun addStaticRoute(destination: NodeId, via: NodeId): Result<Unit>
    suspend fun removeRoute(destination: NodeId): Result<Unit>
    suspend fun startFieldTest(config: FieldTestConfig): Result<String>
    suspend fun stopFieldTest(): Result<Unit>
    suspend fun acknowledgeSos(id: String)
    suspend fun raiseSos(type: Int = 0): Result<String> = Result.failure(UnsupportedOperationException("SOS is not supported by this transport"))
    suspend fun sendCommandNotice(destination: NodeId, kind: CommandNoticeKind, target: NodePosition? = null): Result<String> =
        Result.failure(UnsupportedOperationException("Command Map is not supported by this transport"))
    suspend fun refreshDeviceUiState(): Result<DeviceUiState> =
        Result.failure(UnsupportedOperationException("Device UI OS is not supported by this transport"))
    suspend fun sendDeviceUiAction(action: DeviceUiAction): Result<DeviceUiState> =
        Result.failure(UnsupportedOperationException("Device UI OS is not supported by this transport"))
    suspend fun refreshOledFramebuffer(): Result<OledFramebufferSnapshot> =
        Result.failure(UnsupportedOperationException("Exact OLED framebuffer is not supported by this transport"))
    suspend fun refreshVanguardState(): Result<Unit> = Result.failure(UnsupportedOperationException("VANGUARD is not supported by this transport"))
    suspend fun setManifest(epoch: Long, nodes: List<NodeId>): Result<VanguardManifest> = Result.failure(UnsupportedOperationException("Manifest is not supported"))
    suspend fun discoverRoute(destination: NodeId, forceFresh: Boolean = true): Result<VanguardDiagnostics> = Result.failure(UnsupportedOperationException("Dynamic routing is not supported"))
    suspend fun clearDynamicRoutes(): Result<VanguardDiagnostics> = Result.failure(UnsupportedOperationException("Dynamic routing is not supported"))
    suspend fun injectLinkFailure(peer: NodeId, durationMs: Long): Result<VanguardDiagnostics> = Result.failure(UnsupportedOperationException("Fault Lab is not supported"))
    suspend fun setLabLinkPolicy(peer: NodeId, preset: LabLinkPreset, durationMs: Long): Result<List<LabLinkPolicy>> = Result.failure(UnsupportedOperationException("Fault Lab is not supported"))
    suspend fun clearBleRadar(): Result<Unit> = Result.failure(UnsupportedOperationException("BLE Radar is not supported"))
}
