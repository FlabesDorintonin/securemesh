package dev.securemesh.commander

import android.content.Context
import dev.securemesh.commander.core.database.SecureMeshDatabase
import dev.securemesh.commander.core.map.OfflineMapManager
import dev.securemesh.commander.core.settings.SettingsStore
import dev.securemesh.commander.core.security.SensitiveDataCipher
import dev.securemesh.commander.data.ble.BleDiscoveryParityTransport
import dev.securemesh.commander.data.mock.MockTransport
import dev.securemesh.commander.data.repository.SecureMeshRepositoryImpl
import dev.securemesh.commander.data.transport.TransportRouter
import dev.securemesh.commander.domain.repository.SecureMeshRepository

class AppContainer(context: Context) {
    val offlineMapManager = OfflineMapManager(context)
    private val database = SecureMeshDatabase.create(context)
    private val settingsStore = SettingsStore(context)
    private val sensitiveDataCipher = SensitiveDataCipher()
    private val mockTransport = MockTransport()

    // Discovery deliberately mirrors the scanner entry point that is proven on the physical
    // SecureMesh gateway/phone pair. GATT + Protocol v0.2 remain inside the existing BleTransport
    // delegate used by this discovery-only compatibility layer.
    private val bleTransport = BleDiscoveryParityTransport(context)
    private val router = TransportRouter(mockTransport, bleTransport)

    val repository: SecureMeshRepository = SecureMeshRepositoryImpl(
        router = router,
        mockTransport = mockTransport,
        dao = database.dao(),
        settingsStore = settingsStore,
        sensitiveDataCipher = sensitiveDataCipher,
    )
}
