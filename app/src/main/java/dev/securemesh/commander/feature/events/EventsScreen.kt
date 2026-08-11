package dev.securemesh.commander.feature.events

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.export.LocalDocumentExporter
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(viewModel: EventsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.allowed) {
        EmptyState("Журнал недоступен", "Текущая защищённая сессия не разрешает просмотр системных событий.")
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<MeshEvent?>(null) }
    var pending by remember { mutableStateOf("") }
    var exportOpen by remember { mutableStateOf(false) }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { LocalDocumentExporter.write(context, it, pending) } }
    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { LocalDocumentExporter.write(context, it, pending) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("События", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Локальный журнал видимых событий SecureMesh", color = SecureMeshColors.Muted)
                }
                IconButton(onClick = { exportOpen = true }) { Icon(Icons.Rounded.Download, contentDescription = "Экспорт") }
            }
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::query,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Поиск по журналу") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { FilterChip(state.category == null, { viewModel.category(null) }, { Text("Все") }) }
                items(EventCategory.entries, key = { it.name }) { category ->
                    FilterChip(state.category == category, { viewModel.category(category) }, { Text(category.ruLabel()) })
                }
            }
        }

        if (state.items.isEmpty()) {
            item { EmptyState("Событий нет", "Для выбранного фильтра ничего не найдено.") }
        } else {
            items(state.items, key = { it.id }) { event ->
                EventRow(event) { selected = event }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (exportOpen) {
        ModalBottomSheet(onDismissRequest = { exportOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Экспорт журнала", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Файл сохраняется локально через системный выбор папки.", color = SecureMeshColors.Muted)
                Button(
                    onClick = { scope.launch { pending = viewModel.exportCsv(); createCsv.launch("securemesh-events.csv") }; exportOpen = false },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить CSV") }
                OutlinedButton(
                    onClick = { scope.launch { pending = viewModel.exportJson(); createJson.launch("securemesh-events.json") }; exportOpen = false },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить JSON") }
            }
        }
    }

    selected?.let { event ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusChip(event.category.ruLabel(), categoryColor(event.category))
                Text(localizedTechnicalText(event.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(localizedTechnicalText(event.details), color = SecureMeshColors.TextSecondary)
                TechnicalCard("Детали") {
                    Metric("Время", clockLabel(event.timestampEpochMs))
                    Metric("ID события", event.id)
                    event.nodeId?.let { Metric("Узел", it) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: MeshEvent, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = SecureMeshColors.Surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = categoryColor(event.category).copy(alpha = .13f)) {
                Box(Modifier.padding(10.dp).size(10.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge, color = categoryColor(event.category)) {}
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(localizedTechnicalText(event.title), fontWeight = FontWeight.SemiBold)
                    Text(clockLabel(event.timestampEpochMs), color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(localizedTechnicalText(event.details), color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(event.category.ruLabel(), color = categoryColor(event.category), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun categoryColor(category: EventCategory) = when (category) {
    EventCategory.SOS -> SecureMeshColors.Critical
    EventCategory.ROUTING, EventCategory.RADIO -> SecureMeshColors.Cyan
    EventCategory.SECURITY -> SecureMeshColors.Warning
    else -> SecureMeshColors.Healthy
}
