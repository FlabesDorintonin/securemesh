package dev.securemesh.commander.feature.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddRoad
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(viewModel: RoutesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    if (!state.canView) {
        EmptyState("Маршруты недоступны", "Текущая защищённая сессия не разрешает просмотр таблицы маршрутов.")
        return
    }

    var showAdd by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("") }
    var via by remember { mutableStateOf("") }
    val visibleError = localizedError(error)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Маршруты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Куда сеть отправит следующий hop", color = SecureMeshColors.Muted)
                }
                if (state.canManage) {
                    FilledIconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.AddRoad, contentDescription = "Добавить маршрут") }
                }
            }
        }

        item {
            Text(
                "Количество переходов и качество показываются только если их реально сообщил протокол. Неизвестные значения остаются пустыми.",
                color = SecureMeshColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (visibleError != null) {
            item { Text(visibleError, color = SecureMeshColors.Critical) }
        }

        if (state.routes.isEmpty()) {
            item { EmptyState("Маршрутов пока нет", "В текущей сессии не опубликовано ни одного маршрута.") }
        } else {
            items(state.routes, key = { it.destination }) { route ->
                RouteCard(route, state.canManage && route.type == RouteType.STATIC) {
                    viewModel.remove(route.destination)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Статический маршрут", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Выбери конечный узел и следующий hop.", color = SecureMeshColors.Muted)
                RouteNodeSelector("Куда", destination, state.nodes) { destination = it }
                RouteNodeSelector("Через", via, state.nodes.filter { it.id != destination }) { via = it }
                Button(
                    onClick = { viewModel.add(destination, via); showAdd = false },
                    enabled = destination.isNotBlank() && via.isNotBlank() && destination != via,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить маршрут") }
            }
        }
    }
}

@Composable
private fun RouteCard(route: MeshRoute, removable: Boolean, onRemove: () -> Unit) {
    TechnicalCard("${route.destination} → ${route.nextHop}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Тип", route.type.ruLabel(), Modifier.weight(1f))
            Metric("Переходы", route.hopCount?.toString() ?: "—", Modifier.weight(1f))
            Metric("Качество", route.quality?.let(::percent) ?: "—", Modifier.weight(1f))
        }
        Text("Обновлено: ${route.updatedAtEpochMs?.let(::ageLabel) ?: "нет данных"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        route.path?.takeIf { it.isNotEmpty() }?.let { path ->
            Text("Путь: ${path.joinToString(" → ")}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (removable) {
            TextButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun RouteNodeSelector(label: String, value: String, nodes: List<MeshNode>, set: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${nodes.firstOrNull { it.id == value }?.let { deviceDisplayName(it.name) } ?: value.ifBlank { "выбрать узел" }}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = { Text("${deviceDisplayName(node.name)} · ${node.id}") },
                    onClick = { set(node.id); expanded = false },
                )
            }
        }
    }
}
