package dev.securemesh.commander.core.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.securemesh.commander.domain.model.AppSettings
import dev.securemesh.commander.domain.model.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.secureMeshDataStore by preferencesDataStore(name = "securemesh_settings")

interface SettingsDataSource {
    val settings: Flow<AppSettings>
    /** Internal privacy metadata, not a user preference. Histories are never shared across local node identities. */
    val localHistoryOwnerNodeId: Flow<NodeId?>
    suspend fun write(settings: AppSettings)
    suspend fun setLocalHistoryOwnerNodeId(nodeId: NodeId?)
}

class SettingsStore(private val context: Context) : SettingsDataSource {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val units = stringPreferencesKey("units")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val scanDuration = intPreferencesKey("scan_duration")
        val showUnknown = booleanPreferencesKey("show_unknown_ble")
        val rememberTrustedNode = booleanPreferencesKey("remember_commander") // keep storage key for migration compatibility
        val secureScreen = booleanPreferencesKey("secure_screen")
        val positionHistory = booleanPreferencesKey("position_history")
        val storeEvents = booleanPreferencesKey("store_events")
        val retentionDays = intPreferencesKey("retention_days")
        val mockMode = booleanPreferencesKey("mock_mode")
        val rawBle = booleanPreferencesKey("raw_ble")
        val verboseLogs = booleanPreferencesKey("verbose_logs")
        val simulateFailures = booleanPreferencesKey("simulate_failures")
        val developerMode = booleanPreferencesKey("developer_mode")
        val localHistoryOwnerNodeId = stringPreferencesKey("local_history_owner_node_id")
    }

    override val localHistoryOwnerNodeId: Flow<NodeId?> = context.secureMeshDataStore.data.map { it[Keys.localHistoryOwnerNodeId] }

    override val settings: Flow<AppSettings> = context.secureMeshDataStore.data.map { p ->
        AppSettings(
            theme = p[Keys.theme] ?: "DARK",
            units = p[Keys.units] ?: "METRIC",
            keepScreenAwakeDuringTest = p[Keys.keepAwake] ?: true,
            autoReconnect = p[Keys.autoReconnect] ?: true,
            scanDurationSec = (p[Keys.scanDuration] ?: 12).coerceIn(5, 30),
            showUnknownBle = p[Keys.showUnknown] ?: false,
            rememberTrustedNode = p[Keys.rememberTrustedNode] ?: true,
            secureScreen = p[Keys.secureScreen] ?: true,
            positionHistory = p[Keys.positionHistory] ?: true,
            storeEvents = p[Keys.storeEvents] ?: true,
            retentionDays = (p[Keys.retentionDays] ?: 30).coerceIn(1, 365),
            mockMode = p[Keys.mockMode] ?: false,
            rawBle = p[Keys.rawBle] ?: false,
            verboseLogs = p[Keys.verboseLogs] ?: false,
            simulateFailures = p[Keys.simulateFailures] ?: false,
            developerMode = p[Keys.developerMode] ?: false,
        )
    }

    override suspend fun setLocalHistoryOwnerNodeId(nodeId: NodeId?) {
        context.secureMeshDataStore.edit { p ->
            if (nodeId == null) p.remove(Keys.localHistoryOwnerNodeId) else p[Keys.localHistoryOwnerNodeId] = nodeId
        }
    }

    override suspend fun write(settings: AppSettings) {
        context.secureMeshDataStore.edit { p ->
            p[Keys.theme] = settings.theme
            p[Keys.units] = settings.units
            p[Keys.keepAwake] = settings.keepScreenAwakeDuringTest
            p[Keys.autoReconnect] = settings.autoReconnect
            p[Keys.scanDuration] = settings.scanDurationSec
            p[Keys.showUnknown] = settings.showUnknownBle
            p[Keys.rememberTrustedNode] = settings.rememberTrustedNode
            p[Keys.secureScreen] = settings.secureScreen
            p[Keys.positionHistory] = settings.positionHistory
            p[Keys.storeEvents] = settings.storeEvents
            p[Keys.retentionDays] = settings.retentionDays
            p[Keys.mockMode] = settings.mockMode
            p[Keys.rawBle] = settings.rawBle
            p[Keys.verboseLogs] = settings.verboseLogs
            p[Keys.simulateFailures] = settings.simulateFailures
            p[Keys.developerMode] = settings.developerMode
        }
    }
}
