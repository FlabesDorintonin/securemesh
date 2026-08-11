package dev.securemesh.commander.feature.fieldtest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
fun FieldTestScreen(vm: FieldTestViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    if (!state.canRun) {
        EmptyState("Field Test unavailable", "RUN_FIELD_TEST or FIELD_TEST capability is unavailable.")
        return
    }

    val local = state.localNodeId
    if (local == null) {
        EmptyState("No local node", "SecureMesh session identity is required.")
        return
    }

    val remotes = state.nodes.filter { it.id != local }
    var target by remember(remotes) { mutableStateOf(remotes.firstOrNull()?.id.orEmpty()) }
    var mode by remember { mutableStateOf(FieldTestMode.AUTO) }
    val activeTest = state.active

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("FIELD TEST LAB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Source is locked to local BLE-connected node: $local", color = SecureMeshColors.Muted)
        }

        item {
            TechnicalCard("Configuration") {
                Metric("Source", local)
                Selector(target, remotes) { target = it }
                Row {
                    FieldTestMode.entries.forEach { candidate ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = { mode = candidate },
                            label = { Text(candidate.name) },
                        )
                    }
                }
                if (activeTest?.running == true) {
                    Button(onClick = vm::stop) { Text("STOP TEST") }
                } else {
                    Button(
                        onClick = { vm.start(FieldTestConfig(local, target, mode, 100, 1000, 32)) },
                        enabled = target.isNotBlank(),
                    ) {
                        Text("START TEST")
                    }
                }
            }
        }

        if (activeTest != null) {
            item { Live(activeTest) }
            item {
                Chart(
                    "RSSI dBm",
                    activeTest.points.flatMap { point -> point.rssiSamples().map(Int::toDouble) },
                    -120.0,
                    -35.0,
                    SecureMeshColors.Cyan,
                )
            }
            item {
                Chart(
                    "SNR dB",
                    activeTest.points.flatMap { it.snrSamples() },
                    -15.0,
                    15.0,
                    SecureMeshColors.Warning,
                )
            }
            item {
                TechnicalCard("Per-hop latest") {
                    activeTest.points.lastOrNull()?.hopResults?.forEach { hop ->
                        Text(
                            "${hop.from} → ${hop.to} · ${hop.ackState} · RSSI ${dbm(hop.rssi)} · SNR ${snr(hop.snr)} · retries ${hop.retries ?: "UNKNOWN"}",
                        )
                    }
                }
            }
        }

        item { Text("TEST HISTORY", fontWeight = FontWeight.Bold) }
        items(history, key = { it.id }) { test ->
            TechnicalCard(test.id) {
                Text("${test.config.source} → ${test.config.target}")
                val pdr = test.pdr?.let { "%.1f%%".format(it * 100) } ?: "UNKNOWN"
                Text("Sent ${test.sent} · E2E PDR $pdr · retries ${test.retries}", color = SecureMeshColors.Muted)
            }
        }
    }
}

@Composable
private fun Selector(value: String, nodes: List<MeshNode>, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("Target: ${nodes.firstOrNull { it.id == value }?.name ?: "Select"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = { Text("${node.name} · ${node.id}") },
                    onClick = {
                        set(node.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Live(test: FieldTestSession) {
    TechnicalCard("Live telemetry") {
        Metric("Sent", test.sent.toString())
        Metric("Confirmed received", test.confirmedReceived?.toString() ?: "UNKNOWN")
        Metric("Confirmed lost", test.confirmedLost?.toString() ?: "UNKNOWN")
        Metric("E2E PDR", test.pdr?.let { "%.1f%%".format(it * 100) } ?: "UNKNOWN")
        Metric("Retries", test.retries.toString())
        Text("Route ${test.route.joinToString(" → ")}", color = SecureMeshColors.Muted)
    }
}

@Composable
private fun Chart(title: String, values: List<Double>, min: Double, max: Double, color: Color) {
    TechnicalCard(title) {
        if (values.size < 2) {
            Text("Waiting for telemetry…", color = SecureMeshColors.Muted)
        } else {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = index.toFloat() / (values.size - 1) * size.width
                    val y = (size.height - ((value - min) / (max - min)).toFloat() * size.height)
                        .coerceIn(0f, size.height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(3f))
                drawLine(
                    SecureMeshColors.Muted.copy(alpha = .2f),
                    Offset(0f, size.height / 2),
                    Offset(size.width, size.height / 2),
                    1f,
                )
            }
        }
    }
}
