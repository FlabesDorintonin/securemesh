package dev.securemesh.commander.data.transport

import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.flow.StateFlow

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
    suspend fun stopFieldTest()
    suspend fun acknowledgeSos(id: String)
}
