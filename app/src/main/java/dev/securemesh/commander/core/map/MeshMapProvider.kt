package dev.securemesh.commander.core.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class MapPointKind {
    NODE,
    WAYPOINT,
    SOS,
}

data class MapPoint(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val kind: MapPointKind = MapPointKind.NODE,
    val online: Boolean? = null,
)

data class MapRenderState(
    val points: List<MapPoint>,
    val selectedPointId: String?,
    val followSelected: Boolean,
    val fitAllRequest: Int = 0,
)

interface MeshMapProvider {
    val providerName: String
    val offlineCapable: Boolean

    @Composable
    fun Render(
        state: MapRenderState,
        modifier: Modifier,
        onPointSelected: (String) -> Unit,
    )
}
