package dev.securemesh.commander.feature.map

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.map.*
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.feature.network.NetworkViewModel

@Composable
fun MapScreen(
    viewModel: NetworkViewModel,
    provider: MeshMapProvider = LocalSchematicMapProvider,
    onNode: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.canMap) {
        EmptyState("Карта недоступна", "Нужен GPS и разрешение текущей сессии на просмотр доступных позиций.")
        return
    }

    var selected by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(false) }
    val positioned = state.mapNodes.filter { it.position != null }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Карта", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Показываются только координаты, разрешённые текущей защищённой сессией", color = SecureMeshColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = follow,
                onClick = { follow = !follow },
                label = { Text("Следовать") },
                leadingIcon = { Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp)) },
                enabled = selected != null,
            )
            OutlinedButton(onClick = { selected = null; follow = false }) {
                Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Вся сеть")
            }
            selected?.let { id -> TextButton(onClick = { onNode(id) }) { Text("Открыть узел") } }
        }
        Surface(Modifier.fillMaxWidth().weight(1f), color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.large) {
            if (positioned.isEmpty()) {
                EmptyState("Нет координат", "Приложение не подставляет вымышленные GPS-данные.")
            } else {
                provider.Render(MapRenderState(positioned, selected, follow), Modifier.fillMaxSize()) { selected = it }
            }
        }
        selected?.let { id ->
            positioned.firstOrNull { it.id == id }?.position?.let { position ->
                TechnicalCard("Выбранный узел") {
                    Text("$id · ${coordinate(position.latitude)}, ${coordinate(position.longitude)}", fontWeight = FontWeight.SemiBold)
                    Text("GPS: ${position.status(System.currentTimeMillis()).ruLabel()} · спутники ${position.satellites ?: "—"} · HDOP ${position.hdop ?: "—"}", color = SecureMeshColors.Muted)
                }
            }
        }
    }
}
