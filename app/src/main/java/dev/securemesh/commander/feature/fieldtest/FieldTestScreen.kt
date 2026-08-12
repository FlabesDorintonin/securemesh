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
        EmptyState("Тест связи недоступен", "Узел должен поддерживать FIELD_TEST, а текущая сессия — разрешать его запуск.")
        return
    }

    val local = state.localNodeId ?: return EmptyState("Нет локального узла", "Сначала подключись к SecureMesh.")
    val remotes = remember(state.nodes, local) { state.nodes.filter { it.id != local } }
    var target by remember(remotes) { mutableStateOf(remotes.firstOrNull()?.id.orEmpty()) }
    var mode by remember { mutableStateOf(FieldTestMode.AUTO) }
    val active = state.active

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { OsScreenHeader("Тест связи", "Проверка реального маршрута без подмены hop-ACK финальной доставкой") }

        if (active?.running == true) {
            item { LiveTelemetry(active, onStop = viewModel::stop) }
        } else {
            item {
                OsHeroCard(
                    eyebrow = "Диагностика",
                    title = "Проверить связь до узла",
                    subtitle = "DIAG_PING идёт обычным routing path. E2E PONG подтверждает полный путь туда и обратно.",
                    accent = SecureMeshColors.Cyan,
                ) {
                    TargetSelector(target, remotes) { target = it }
                    Text("Режим маршрута", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(FieldTestMode.entries, key = { it.name }) { candidate ->
                            FilterChip(selected = mode == candidate, onClick = { mode = candidate }, label = { Text(candidate.ruLabel()) })
                        }
                    }
                    VibrantPrimaryButton(
                        text = "Запустить тест · 100 пакетов",
                        onClick = { viewModel.start(FieldTestConfig(local, target, mode, 100, 1000, 32)) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.PlayArrow,
                        enabled = target.isNotBlank(),
                    )
                }
            }
            active?.let { item { LiveTelemetry(it, onStop = null) } }
        }

        active?.let { test ->
            val rssiPoints = remember(test.points) { test.points.flatMap { point -> point.rssiSamples().map(Int::toDouble) } }
            val snrPoints = remember(test.points) { test.points.flatMap { point -> point.snrSamples() } }
            if (rssiPoints.size >= 2 || snrPoints.size >= 2) item { SectionHeader("Графики радио") }
            if (rssiPoints.size >= 2) item { Chart("RSSI · dBm", rssiPoints, -120.0, -35.0, SecureMeshColors.Cyan) }
            if (snrPoints.size >= 2) item { Chart("SNR · dB", snrPoints, -15.0, 15.0, SecureMeshColors.Warning) }

            val latest = test.points.lastOrNull()?.hopResults.orEmpty()
            if (latest.isNotEmpty()) {
                item {
                    TechnicalCard("Последний radio hop") {
                        latest.forEachIndexed { index, hop ->
                            if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider)
                            Text("${hop.from} → ${hop.to}", fontWeight = FontWeight.SemiBold)
                            Text("${hop.ackState.ruLabel()} · RSSI ${dbm(hop.rssi)} · SNR ${snr(hop.snr)} · повторы ${hop.retries ?: "—"}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item { SectionHeader("История") }
        if (history.isEmpty()) item { Text("Завершённых тестов пока нет", color = SecureMeshColors.Muted) }
        else items(history, key = { it.id }) { test ->
            Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large, border = androidx.compose.foundation.BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .72f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${test.config.source} → ${test.config.target}", fontWeight = FontWeight.Bold)
                        StatusChip(test.pdr?.let(::percent) ?: "нет E2E", if ((test.pdr ?: 0.0) >= .9) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OsStat("Отправлено", test.sent.toString(), SecureMeshColors.Cyan, Modifier.weight(1f))
                        OsStat("PDR E2E", test.pdr?.let(::percent) ?: "—", SecureMeshColors.Healthy, Modifier.weight(1f))
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
    val selected = nodes.firstOrNull { it.id == value }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.weight(1f)) {
                Text(selected?.let { deviceDisplayName(it.name) } ?: "Выбрать узел", fontWeight = FontWeight.SemiBold)
                if (selected != null) Text(selected.id, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text("›", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(deviceDisplayName(node.name), fontWeight = FontWeight.SemiBold)
                            Text(node.id, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    onClick = { set(node.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun LiveTelemetry(test: FieldTestSession, onStop: (() -> Unit)?) {
    val requested = test.config.packetCount.coerceAtLeast(1)
    val progress = (test.sent.toFloat() / requested.toFloat()).coerceIn(0f, 1f)
    val pdr = test.pdr
    OsHeroCard(
        eyebrow = if (test.running) "Тест идёт" else "Последний результат",
        title = "${deviceDisplayName(test.config.target)} · ${test.sent}/$requested",
        subtitle = if (test.running) "Пакеты отправляются без блокировки интерфейса" else "Сохранённый результат последнего запуска",
        accent = if (test.running) SecureMeshColors.Cyan else SecureMeshColors.Healthy,
        status = if (test.running) "RUNNING" else "ГОТОВО",
    ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(7.dp), color = SecureMeshColors.Cyan, trackColor = SecureMeshColors.Divider)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OsStat("E2E PDR", pdr?.let(::percent) ?: "—", if ((pdr ?: 0.0) >= .9) SecureMeshColors.Healthy else SecureMeshColors.Warning, Modifier.weight(1f))
            OsStat("PONG", test.confirmedReceived?.toString() ?: "—", SecureMeshColors.Healthy, Modifier.weight(1f))
            OsStat("Потери", test.confirmedLost?.toString() ?: "—", SecureMeshColors.Warning, Modifier.weight(1f))
        }
        Text("Первый radio hop", color = SecureMeshColors.TextSecondary, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OsStat("ACK", test.firstHopAcked?.toString() ?: "—", SecureMeshColors.Blue, Modifier.weight(1f))
            OsStat("Fail", test.firstHopFailures?.toString() ?: "—", if ((test.firstHopFailures ?: 0) > 0) SecureMeshColors.Warning else SecureMeshColors.Muted, Modifier.weight(1f))
            OsStat("Retry", test.retries.toString(), SecureMeshColors.Cyan, Modifier.weight(1f))
        }
        Text("RTT по DIAG_PONG", color = SecureMeshColors.TextSecondary, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OsStat("min", test.rttMinimumMs?.let { "$it мс" } ?: "—", SecureMeshColors.Cyan, Modifier.weight(1f))
            OsStat("avg", test.rttAverageMs?.let { "$it мс" } ?: "—", SecureMeshColors.Healthy, Modifier.weight(1f))
            OsStat("max", test.rttMaximumMs?.let { "$it мс" } ?: "—", SecureMeshColors.Warning, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1-hop RSSI: ${test.averageRssi()?.let { "%.1f dBm".format(it) } ?: "—"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text("SNR: ${test.averageSnr()?.let { "%.1f dB".format(it) } ?: "—"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("nextHop: ${test.currentNextHop ?: "нет данных"} · это не полный path trace", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
        if (onStop != null) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = SecureMeshColors.Critical)) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Остановить тест", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Chart(title: String, values: List<Double>, min: Double, max: Double, color: Color) {
    TechnicalCard(title) {
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = index.toFloat() / (values.size - 1) * size.width
                val y = (size.height - ((value - min) / (max - min)).toFloat() * size.height).coerceIn(0f, size.height)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(SecureMeshColors.Muted.copy(alpha = .15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            drawPath(path, color, style = Stroke(3f))
        }
    }
}
