package dev.securemesh.commander.feature.fieldtest

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
        EmptyState("Полевой тест недоступен", "Узел должен поддерживать тестирование, а сессия — разрешать его запуск.")
        return
    }

    val local = state.localNodeId ?: return EmptyState("Нет локального узла", "Сначала нужна установленная сессия SecureMesh.")
    val remotes = state.nodes.filter { it.id != local }
    var target by remember(remotes) { mutableStateOf(remotes.firstOrNull()?.id.orEmpty()) }
    var mode by remember { mutableStateOf(FieldTestMode.AUTO) }
    val active = state.active

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Полевой тест", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Реальная проверка radio/routing-пути от локального BLE-узла", color = SecureMeshColors.Muted)
        }

        item {
            TechnicalCard("Параметры") {
                Metric("Источник", local)
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
                        Text("Остановить тест")
                    }
                } else {
                    Button(
                        onClick = { viewModel.start(FieldTestConfig(local, target, mode, 100, 1000, 32)) },
                        enabled = target.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Запустить 100 пакетов")
                    }
                }
            }
        }

        if (active != null) {
            item { LiveTelemetry(active) }
            item { Chart("RSSI · dBm", active.points.flatMap { point -> point.rssiSamples().map(Int::toDouble) }, -120.0, -35.0, SecureMeshColors.Cyan) }
            item { Chart("SNR · dB", active.points.flatMap { it.snrSamples() }, -15.0, 15.0, SecureMeshColors.Warning) }
            item {
                TechnicalCard("Последний пакет по hop") {
                    val latest = active.points.lastOrNull()?.hopResults.orEmpty()
                    if (latest.isEmpty()) Text("Ждём телеметрию…", color = SecureMeshColors.Muted)
                    latest.forEachIndexed { index, hop ->
                        if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider)
                        Text("${hop.from} → ${hop.to}", fontWeight = FontWeight.SemiBold)
                        Text("${hop.ackState.ruLabel()} · RSSI ${dbm(hop.rssi)} · SNR ${snr(hop.snr)} · повторы ${hop.retries ?: "—"}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { SectionHeader("История тестов") }
        if (history.isEmpty()) {
            item { Text("Завершённых тестов пока нет", color = SecureMeshColors.Muted) }
        } else {
            items(history, key = { it.id }) { test ->
                TechnicalCard("${test.config.source} → ${test.config.target}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("Пакетов", test.sent.toString(), Modifier.weight(1f))
                        Metric("PDR E2E", test.pdr?.let(::percent) ?: "—", Modifier.weight(1f))
                        Metric("Повторов", test.retries.toString(), Modifier.weight(1f))
                    }
                    Text("${test.config.mode.ruLabel()} · ${if (test.running) "идёт сейчас" else "завершён"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
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
            Text("Цель: ${nodes.firstOrNull { it.id == value }?.let { deviceDisplayName(it.name) } ?: "выбрать узел"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(text = { Text("${deviceDisplayName(node.name)} · ${node.id}") }, onClick = { set(node.id); open = false })
            }
        }
    }
}

@Composable
private fun LiveTelemetry(test: FieldTestSession) {
    TechnicalCard(if (test.running) "Тест идёт" else "Последний результат") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Отправлено", test.sent.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
            MetricTile("PDR E2E", test.pdr?.let(::percent) ?: "—", Modifier.weight(1f), if (test.pdr != null) SecureMeshColors.Healthy else SecureMeshColors.Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Подтверждено", test.confirmedReceived?.toString() ?: "—")
            Metric("Потеряно", test.confirmedLost?.toString() ?: "—")
            Metric("Повторы", test.retries.toString())
        }
        Text("Маршрут: ${test.route.joinToString(" → ")}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Chart(title: String, values: List<Double>, min: Double, max: Double, color: Color) {
    TechnicalCard(title) {
        if (values.size < 2) {
            Text("Ждём телеметрию…", color = SecureMeshColors.Muted)
        } else {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = index.toFloat() / (values.size - 1) * size.width
                    val y = (size.height - ((value - min) / (max - min)).toFloat() * size.height).coerceIn(0f, size.height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawLine(SecureMeshColors.Muted.copy(alpha = .18f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                drawPath(path, color, style = Stroke(3.5f))
            }
        }
    }
}
