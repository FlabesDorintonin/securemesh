package dev.securemesh.commander.core.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.securemesh.commander.domain.model.MeshNode

data class MapRenderState(
    val nodes: List<MeshNode>,
    val selectedNodeId: String?,
    val followSelected: Boolean,
)

interface MeshMapProvider {
    val providerName: String
    val offlineCapable: Boolean

    @Composable
    fun Render(
        state: MapRenderState,
        modifier: Modifier,
        onNodeSelected: (String) -> Unit,
    )
}
