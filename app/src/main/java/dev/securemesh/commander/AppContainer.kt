package dev.securemesh.commander

import android.content.Context
import dev.securemesh.commander.core.database.SecureMeshDatabase
import dev.securemesh.commander.core.settings.SettingsStore
import dev.securemesh.commander.data.ble.BleTransport
import dev.securemesh.commander.data.mock.MockTransport
import dev.securemesh.commander.data.repository.SecureMeshRepositoryImpl
import dev.securemesh.commander.data.transport.TransportRouter
import dev.securemesh.commander.domain.repository.SecureMeshRepository

class AppContainer(context: Context) {
    private val database = SecureMeshDatabase.create(context)
    private val settingsStore = SettingsStore(context)
    private val mockTransport = MockTransport()
    private val bleTransport = BleTransport(context)
    private val router = TransportRouter(mockTransport, bleTransport)

    val repository: SecureMeshRepository = SecureMeshRepositoryImpl(
        router = router,
        mockTransport = mockTransport,
        dao = database.dao(),
        settingsStore = settingsStore,
    )
}
