package dev.securemesh.commander.feature.fieldtest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun FieldTestScreen(viewModel: FieldTestViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    if (!state.canRun) {
        EmptyState("Проверка связи недоступна", "Подключите свой узел SecureMesh и подтвердите доступ.")
        return
    }

    val local = state.localNodeId ?: return EmptyState("Нет подключённого узла", "Сначала подключите устройство SecureMesh.")
    val remotes = state.nodes.filter { it.id != local }
    val localName = state.nodes.firstOrNull { it.id == local }?.name?.let(::deviceDisplayName) ?: "Мой узел"
    var target by remember(remotes) { mutableStateOf(remotes.firstOrNull()?.id.orEmpty()) }
    var mode by remember { mutableStateOf(FieldTestMode.AUTO) }
    val active = state.active

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Проверка связи", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Проверяет, насколько надёжно данные доходят до выбранного узла и возвращается ответ.",
                color = SecureMeshColors.Muted,
            )
        }

        item {
            TechnicalCard("Параметры") {
                Metric("Откуда", localName)
                TargetSelector(target, remotes) { target = it }
                Text("Режим", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(FieldTestMode.entries, key = { it.name }) { candidate ->
                        FilterChip(mode == candidate, { mode = candidate }, { Text(candidate.ruLabel()) })
                    }
                }
                if (active?.running == true) {
                    Button(onClick = viewModel::stop, colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Critical)) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Остановить проверку")
                    }
                } else {
                    Button(
                        onClick = { viewModel.start(FieldTestConfig(local, target, mode, 100, 1000, 32)) },
                        enabled = target.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Запустить проверку")
                    }
                }
            }
        }

        if (active != null) {
            item {
                LiveResult(
                    test = active,
                    targetName = remotes.firstOrNull { it.id == active.config.target }?.name?.let(::deviceDisplayName) ?: "Выбранный узел",
                )
            }
        }

        item { SectionHeader("История проверок") }
        if (history.isEmpty()) {
            item { Text("Завершённых проверок пока нет", color = SecureMeshColors.Muted) }
        } else {
            items(history, key = { it.id }) { test ->
                val targetName = state.nodes.firstOrNull { it.id == test.config.target }?.name?.let(::deviceDisplayName) ?: "Узел"
                TechnicalCard(targetName) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("Доставлено", test.confirmedReceived?.toString() ?: "—", Modifier.weight(1f))
                        Metric("Потеряно", test.confirmedLost?.toString() ?: "—", Modifier.weight(1f))
                        Metric("Надёжность", reliabilityLabel(test.pdr), Modifier.weight(1f))
                    }
                    Text(
                        "${test.config.mode.ruLabel()} · ${if (test.running) "проверка идёт" else "завершено"}",
                        color = SecureMeshColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TargetSelector(value: String, nodes: List<MeshNode>, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Куда: ${nodes.firstOrNull { it.id == value }?.let { deviceDisplayName(it.name) } ?: "выбрать узел"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = { Text(deviceDisplayName(node.name)) },
                    onClick = { set(node.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun LiveResult(test: FieldTestSession, targetName: String) {
    TechnicalCard(if (test.running) "Проверка идёт" else "Результат") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Отправлено", test.sent.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
            MetricTile("Доставлено", test.confirmedReceived?.toString() ?: "—", Modifier.weight(1f), SecureMeshColors.Healthy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Потеряно", test.confirmedLost?.toString() ?: "—", Modifier.weight(1f), SecureMeshColors.Warning)
            MetricTile("Надёжность", reliabilityLabel(test.pdr), Modifier.weight(1f), reliabilityColor(test.pdr))
        }
        HorizontalDivider(color = SecureMeshColors.Divider)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Ответ", responseLabel(test.rttAverageMs))
            Metric("Ближняя связь", firstLinkLabel(test))
        }
        Text("Цель: $targetName", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun reliabilityLabel(value: Double?): String = when {
    value == null -> "Нет данных"
    value >= .97 -> "Отличная"
    value >= .90 -> "Хорошая"
    value >= .75 -> "Нестабильная"
    else -> "Плохая"
}

private fun reliabilityColor(value: Double?) = when {
    value == null -> SecureMeshColors.Muted
    value >= .97 -> SecureMeshColors.Healthy
    value >= .90 -> SecureMeshColors.Cyan
    value >= .75 -> SecureMeshColors.Warning
    else -> SecureMeshColors.Critical
}

private fun responseLabel(value: Long?): String = when {
    value == null || value <= 0 -> "Нет данных"
    value <= 150 -> "Быстрый"
    value <= 500 -> "Нормальный"
    value <= 1_500 -> "Медленный"
    else -> "Очень медленный"
}

private fun firstLinkLabel(test: FieldTestSession): String = when {
    (test.firstHopFailures ?: 0) > 0 -> "Есть потери"
    (test.firstHopAcked ?: 0) > 0 -> "Стабильно"
    else -> "Нет данных"
}
