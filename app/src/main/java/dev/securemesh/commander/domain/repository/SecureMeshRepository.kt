package dev.securemesh.commander.domain.repository

import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SecureMeshRepository {
    val transportMode: StateFlow<TransportMode>
    val connectionState: StateFlow<MeshConnectionState>
    val session: StateFlow<SecureMeshSession?>
    val demoProfile: StateFlow<DemoProfile?>
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val nodes: StateFlow<List<MeshNode>>
    val topology: StateFlow<MeshTopology>
    val messages: StateFlow<List<MeshMessage>>
    val routes: StateFlow<List<MeshRoute>>
    val activeFieldTest: StateFlow<FieldTestSession?>
    val activeSos: StateFlow<SosAlert?>
    val bleDiagnostics: StateFlow<BleDiagnostics?>
    val deviceUiState: StateFlow<DeviceUiState?>
    val knownNodeIds: StateFlow<List<NodeId>>
    val networkManifest: StateFlow<VanguardManifest?>
    val vanguardDiagnostics: StateFlow<VanguardDiagnostics?>
    val labLinkPolicies: StateFlow<List<LabLinkPolicy>>
    val settings: StateFlow<AppSettings>
    val localHistoryOwnerNodeId: StateFlow<NodeId?>

    fun observeMessageHistory(): Flow<List<MeshMessage>>
    fun observeEvents(): Flow<List<MeshEvent>>
    fun observeFieldTestHistory(): Flow<List<FieldTestSession>>
    fun observePositionHistory(nodeId: NodeId? = null): Flow<List<NodePosition>>

    suspend fun useTransport(mode: TransportMode)
    suspend fun launchDemo(profile: DemoProfile)
    suspend fun applyDemoScenario(name: String)
    suspend fun scanDevices(durationMs: Long? = null)
    suspend fun stopScan()
    suspend fun connect(device: DiscoveredDevice)
    suspend fun disconnect()
    suspend fun cancelReconnect()
    suspend fun attemptAutoReconnect()
    suspend fun sendMessage(destination: NodeId, payload: String): Result<MessageId>
    suspend fun addStaticRoute(destination: NodeId, via: NodeId): Result<Unit>
    suspend fun removeRoute(destination: NodeId): Result<Unit>
    suspend fun startFieldTest(config: FieldTestConfig): Result<String>
    suspend fun stopFieldTest()
    suspend fun acknowledgeSos(id: String)
    suspend fun refreshDeviceUiState(): Result<DeviceUiState>
    suspend fun sendDeviceUiAction(action: DeviceUiAction): Result<DeviceUiState>
    suspend fun refreshVanguardState(): Result<Unit>
    suspend fun setManifest(epoch: Long, nodes: List<NodeId>): Result<VanguardManifest>
    suspend fun discoverRoute(destination: NodeId, forceFresh: Boolean = true): Result<VanguardDiagnostics>
    suspend fun clearDynamicRoutes(): Result<VanguardDiagnostics>
    suspend fun injectLinkFailure(peer: NodeId, durationMs: Long): Result<VanguardDiagnostics>
    suspend fun setLabLinkPolicy(peer: NodeId, preset: LabLinkPreset, durationMs: Long): Result<List<LabLinkPolicy>>
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
    suspend fun clearLocalHistory()
    suspend fun exportEventsCsv(): String
    suspend fun exportEventsJson(): String
    suspend fun exportFieldTestsCsv(): String
    suspend fun exportFieldTestsJson(): String
}
