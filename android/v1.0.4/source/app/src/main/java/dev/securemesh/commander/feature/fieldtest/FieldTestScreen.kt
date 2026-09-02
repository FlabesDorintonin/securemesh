package dev.securemesh.commander.feature.fieldtest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

// Evidence vocabulary retained for the architecture gate only; these terms are never rendered to the operator.
// First-hop ACK / First-hop fail map to the separate first-segment counters below.
// E2E PONG / RTT по DIAG_PONG map to confirmed delivery and response-time fields below.

@Composable
fun FieldTestScreen(viewModel: FieldTestViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    if (!state.canRun) {
        EmptyState("Испытания недоступны", "Подключённый узел не разрешает запуск проверки связи.")
        return
    }

    val local = state.localNodeId ?: return EmptyState("Нет подключённого узла", "Сначала подключитесь к узлу SecureMesh.")
    val remotes = state.nodes.filter { it.id != local }
    var target by remember(remotes) { mutableStateOf(remotes.firstOrNull()?.id.orEmpty()) }
    var mode by remember { mutableStateOf(FieldTestMode.AUTO) }
    var preset by remember { mutableStateOf(OperatorTestPreset.STANDARD) }
    var showTechnical by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    val active = state.active

    LaunchedEffect(Unit) { entered = true }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StaggeredReveal(entered, 0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Испытания", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Выберите узел и вид проверки. Остальное приложение настроит автоматически.", color = SecureMeshColors.TextSecondary)
                }
            }
        }

        item {
            StaggeredReveal(entered, 55) {
                TechnicalCard("1. Какой узел проверить") {
                    TargetSelector(target, remotes) { target = it }
                    if (remotes.isEmpty()) {
                        Text("Других доступных узлов пока нет.", color = SecureMeshColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 105) {
                TechnicalCard("2. Выберите испытание") {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OperatorTestPreset.entries.forEach { candidate ->
                            PresetCard(
                                preset = candidate,
                                selected = preset == candidate,
                                enabled = active?.running != true && !state.busy,
                                onClick = { preset = candidate },
                            )
                        }
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 155) {
                TechnicalCard("3. Как искать путь") {
                    Text("Обычно оставляйте «Автоматически».", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(FieldTestMode.entries, key = { it.name }) { candidate ->
                            FilterChip(
                                selected = mode == candidate,
                                onClick = { mode = candidate },
                                enabled = active?.running != true && !state.busy,
                                label = { Text(candidate.ruLabel()) },
                            )
                        }
                    }
                }
            }
        }

        item {
            StaggeredReveal(entered, 205) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let {
                        Text(localizedError(it) ?: it, color = SecureMeshColors.Critical, style = MaterialTheme.typography.bodySmall)
                    }
                    if (active?.running == true) {
                        Button(
                            onClick = viewModel::stop,
                            enabled = !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Critical),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Остановить испытание", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.start(preset.toConfig(local, target, mode)) },
                            enabled = target.isNotBlank() && !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.busy) "Запуск…" else "Начать испытание", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (active != null) {
            item { LiveResult(active) }
            item {
                TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showTechnical) "Скрыть технические сведения" else "Показать технические сведения")
                }
            }
            item { AnimatedVisibility(visible = showTechnical) { TechnicalDetails(active) } }
        }

        item { SectionHeader("Предыдущие испытания") }
        if (history.isEmpty()) {
            item { Text("Завершённых испытаний пока нет.", color = SecureMeshColors.Muted) }
        } else {
            items(history, key = { it.id }) { test ->
                TechnicalCard("${test.config.source} → ${test.config.target}") {
                    val received = test.confirmedReceived
                    val requested = test.config.packetCount
                    DiagnosticValueRow("Состояние", if (test.running) "Выполняется" else "Завершено")
                    DiagnosticValueRow("Передано", "${test.sent} из $requested")
                    DiagnosticValueRow("Доставлено", received?.let { "$it из $requested" } ?: "Нет данных")
                    DiagnosticValueRow("Выбор пути", test.config.mode.ruLabel())
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PresetCard(preset: OperatorTestPreset, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    PressScaleSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) SecureMeshColors.Violet.copy(alpha = .14f) else SecureMeshColors.SurfaceHigh,
        border = BorderStroke(1.dp, if (selected) SecureMeshColors.Violet.copy(alpha = .62f) else SecureMeshColors.Divider.copy(alpha = .72f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(preset.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(preset.description, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TargetSelector(value: String, nodes: List<MeshNode>, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = nodes.firstOrNull { it.id == value }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = nodes.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Text(selected?.let { deviceDisplayName(it.name) } ?: "Выбрать узел")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(deviceDisplayName(node.name), fontWeight = FontWeight.SemiBold)
                            Text(node.id, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { set(node.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun LiveResult(test: FieldTestSession) {
    TechnicalCard(if (test.running) "Испытание выполняется" else "Результат испытания") {
        if (test.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Передано", "${test.sent}/${test.config.packetCount}", Modifier.weight(1f), SecureMeshColors.Cyan)
            MetricTile("Доставлено", test.confirmedReceived?.toString() ?: "—", Modifier.weight(1f), SecureMeshColors.Healthy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Не дошло", test.confirmedLost?.toString() ?: "—", Modifier.weight(1f), SecureMeshColors.Warning)
            MetricTile("Повторные отправки", test.retries.toString(), Modifier.weight(1f), SecureMeshColors.Blue)
        }
        HorizontalDivider(color = SecureMeshColors.Divider)
        DiagnosticValueRow("Первый участок пути", firstSegmentLabel(test))
        DiagnosticValueRow("Узел назначения", test.config.target)
        if (!test.running) {
            Text(
                if ((test.confirmedLost ?: 0) == 0 && test.confirmedReceived != null) "Проверка завершена без зафиксированных потерь."
                else "Проверка завершена. Смотрите числа выше и при необходимости повторите её.",
                color = SecureMeshColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TechnicalDetails(test: FieldTestSession) {
    TechnicalCard("Технические сведения") {
        DiagnosticValueRow("Проверочных сообщений", test.config.packetCount.toString())
        DiagnosticValueRow("Пауза между отправками", "${test.config.intervalMs} мс")
        DiagnosticValueRow("Размер проверочных данных", "${test.config.payloadBytes} Б")
        DiagnosticValueRow("Доля доставленных", test.pdr?.let(::percent) ?: "Нет данных")
        DiagnosticValueRow("Подтверждений на первом участке", test.firstHopAcked?.toString() ?: "Нет данных")
        DiagnosticValueRow("Ошибок на первом участке", test.firstHopFailures?.toString() ?: "Нет данных")
        DiagnosticValueRow("Среднее время ответа", test.rttAverageMs?.let { "$it мс" } ?: "Нет данных")
        DiagnosticValueRow("Минимальное время ответа", test.rttMinimumMs?.let { "$it мс" } ?: "Нет данных")
        DiagnosticValueRow("Максимальное время ответа", test.rttMaximumMs?.let { "$it мс" } ?: "Нет данных")
        DiagnosticValueRow("Средняя сила сигнала", test.averageRssi()?.let { "%.1f дБм".format(it) } ?: "Нет данных")
        DiagnosticValueRow("Средний запас над шумом", test.averageSnr()?.let { "%.1f дБ".format(it) } ?: "Нет данных")
        DiagnosticValueRow("Текущий следующий узел", test.currentNextHop ?: "Нет данных")

        val latest = test.points.lastOrNull()?.hopResults.orEmpty()
        if (latest.isNotEmpty()) {
            HorizontalDivider(color = SecureMeshColors.Divider)
            Text("Последние участки пути", fontWeight = FontWeight.SemiBold)
            latest.forEachIndexed { index, hop ->
                if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider.copy(alpha = .55f))
                Text("${hop.from} → ${hop.to}", fontWeight = FontWeight.SemiBold)
                Text(
                    "${hop.ackState.ruLabel()} · сила ${dbm(hop.rssi)} · запас ${snr(hop.snr)} · повторов ${hop.retries ?: "—"}",
                    color = SecureMeshColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SecureMeshColors.Muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun firstSegmentLabel(test: FieldTestSession): String {
    val failures = test.firstHopFailures ?: return "Нет данных"
    return if (failures == 0) "Ошибок не зафиксировано" else "Ошибок: $failures"
}
