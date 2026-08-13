@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.securemesh.commander.feature.vanguard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import java.util.Locale

private enum class ControlTab(val title: String) { OVERVIEW("Обзор"), ROUTING("Routing"), FAULT("Fault Lab"), MANIFEST("Manifest"), OLED("OLED") }

@Composable
fun VanguardControlScreen(viewModel: VanguardControlViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(ControlTab.OVERVIEW) }
    var selectedDestination by remember { mutableStateOf<NodeId?>(null) }
    var selectedPeer by remember { mutableStateOf<NodeId?>(null) }
    var duration by remember { mutableLongStateOf(30_000L) }
    var showManifest by remember { mutableStateOf(false) }

    LaunchedEffect(state.session?.localNodeIdentity?.nodeId) {
        if (state.vanguardAvailable) viewModel.refresh()
        if (state.uiOsAvailable && state.deviceUi == null) viewModel.refreshOled()
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("VANGUARD CONTROL", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                    Text("Управление SecureMesh v0.8.2", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
                FilledTonalIconButton(onClick = viewModel::refresh, enabled = !state.busy) { Icon(Icons.Rounded.Refresh, "Обновить") }
            }

            if (!state.vanguardAvailable) {
                EmptyState("VANGUARD недоступен", "Подключённый узел не объявил capability VANGUARD. Для этой панели нужна прошивка SecureMesh v0.8.2 3RADIO_BASE.")
                return@Column
            }

            ScrollableTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, edgePadding = 12.dp) {
                ControlTab.entries.forEach { item ->
                    Tab(selected = tab == item, onClick = { tab = item }, text = { Text(item.title) })
                }
            }

            state.error?.let { error ->
                Surface(color = SecureMeshColors.Critical.copy(alpha = .12f), modifier = Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = SecureMeshColors.Critical, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearError) { Text("Закрыть") }
                    }
                }
            }
            AnimatedVisibility(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.CyanHot) }

            when (tab) {
                ControlTab.OVERVIEW -> OverviewTab(state)
                ControlTab.ROUTING -> RoutingTab(state, selectedDestination, { selectedDestination = it }, viewModel::discover, viewModel::clearRoutes)
                ControlTab.FAULT -> FaultTab(state, selectedPeer, { selectedPeer = it }, duration, { duration = it }, viewModel::lab, viewModel::injectBlock)
                ControlTab.MANIFEST -> ManifestTab(state, onConfigure = { showManifest = true })
                ControlTab.OLED -> OledTab(state, viewModel::oled, viewModel::refreshOled)
            }
        }
    }

    if (showManifest) {
        ManifestDialog(state, onDismiss = { showManifest = false }) { epoch, nodes ->
            viewModel.setManifest(epoch, nodes)
            showManifest = false
        }
    }
}

@Composable
private fun OverviewTab(state: VanguardControlUiState) {
    val diag = state.diagnostics
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(
                color = SecureMeshColors.SurfaceHigh.copy(alpha = .92f),
                shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(1.dp, if (diag?.manifestValid == true) SecureMeshColors.Healthy.copy(alpha=.25f) else SecureMeshColors.Warning.copy(alpha=.28f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(state.session?.localNodeIdentity?.displayName ?: "Локальный узел", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                            Text(state.session?.localNodeIdentity?.nodeId.orEmpty(), color = SecureMeshColors.Muted, fontFamily = FontFamily.Monospace)
                        }
                        StatusChip(if (diag?.manifestValid == true) "MANIFEST OK" else "MANIFEST?", if (diag?.manifestValid == true) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("Known", state.knownNodeIds.size.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
                        MetricTile("Routes", diag?.routes?.size?.toString() ?: "—", Modifier.weight(1f), SecureMeshColors.Blue)
                        MetricTile("Faults", diag?.activeLabFaults?.toString() ?: "—", Modifier.weight(1f), if ((diag?.activeLabFaults ?: 0) > 0) SecureMeshColors.Warning else SecureMeshColors.Healthy)
                    }
                }
            }
        }
        item { SectionHeader("VANGUARD runtime") }
        item {
            TechnicalCard("Маршрутизация") {
                ValueRow("Primary принято", diag?.acceptedPrimary?.toString() ?: "—")
                ValueRow("G2 promotions", diag?.promotionsG2?.toString() ?: "—")
                ValueRow("Alternate promotions", diag?.promotionsAlternate?.toString() ?: "—")
                ValueRow("Loop rejects", diag?.rejectedLoop?.toString() ?: "—")
                ValueRow("Infeasible rejects", diag?.rejectedInfeasible?.toString() ?: "—")
                ValueRow("Route errors", diag?.routeErrors?.toString() ?: "—")
            }
        }
        item {
            TechnicalCard("Airtime / очередь") {
                ValueRow("Control budget drops", diag?.controlBudgetDrops?.toString() ?: "—")
                ValueRow("Budget tokens", diag?.controlBudgetTokensUs?.let { "$it µs" } ?: "—")
                ValueRow("Deferred queued", diag?.deferredQueued?.toString() ?: "—")
                ValueRow("Deferred drops", diag?.deferredDrops?.toString() ?: "—")
                ValueRow("Active deferred", diag?.activeDeferred?.toString() ?: "—")
            }
        }
        if (state.knownNodeIds.isNotEmpty()) {
            item { SectionHeader("Known Registry") }
            items(state.knownNodeIds, key = { it }) { id ->
                val live = state.nodes.firstOrNull { it.id == id }?.online == true
                Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(8.dp), shape = CircleShape, color = if (live) SecureMeshColors.Healthy else SecureMeshColors.Muted) {}
                        Spacer(Modifier.width(10.dp)); Text(id, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(if (live) "в эфире" else "известен", color = if (live) SecureMeshColors.Healthy else SecureMeshColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutingTab(state: VanguardControlUiState, selected: NodeId?, setSelected: (NodeId) -> Unit, discover: (NodeId) -> Unit, clear: () -> Unit) {
    val targets = (state.knownNodeIds + state.nodes.map { it.id }).distinct().filterNot { it == state.session?.localNodeIdentity?.nodeId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TechnicalCard("Route discovery") {
                NodePicker("Цель", selected, targets, setSelected)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selected?.let(discover) }, enabled = selected != null && !state.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Route, null); Spacer(Modifier.width(6.dp)); Text("Force fresh") }
                    OutlinedButton(onClick = clear, enabled = !state.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text("Clear dynamic") }
                }
            }
        }
        if (state.diagnostics?.routes.isNullOrEmpty()) item { EmptyState("Нет VANGUARD routes", "Выбери destination и запусти Force fresh discovery.") }
        else items(state.diagnostics!!.routes, key = { it.destination }) { route -> VanguardRouteCard(route) }
    }
}

@Composable
private fun VanguardRouteCard(route: VanguardRouteDetail) {
    TechnicalCard("DEST ${route.destination}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusChip(if (route.primaryExact) "PRIMARY EXACT" else "PRIMARY", if (route.primaryExact) SecureMeshColors.Healthy else SecureMeshColors.Cyan)
            if (route.exactG2Available) StatusChip("EXACT G2", SecureMeshColors.Violet)
            if (route.primaryPromotedFromBackup) StatusChip("PROMOTED", SecureMeshColors.Warning)
        }
        ValueRow("Primary", route.primaryNextHop ?: "—")
        ValueRow("G2 / backup", route.backupNextHop ?: "—")
        ValueRow("Alternate", route.alternateNextHop ?: "—")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Reliability", "%.1f%%".format(Locale.US, route.primaryReliability * 100), Modifier.weight(1f), SecureMeshColors.Healthy)
            Metric("ECA", "%.2f".format(Locale.US, route.primaryEca), Modifier.weight(1f), SecureMeshColors.Blue)
            Metric("Lease", route.backupLease.toString(), Modifier.weight(1f), SecureMeshColors.Violet)
        }
        ValueRow("Generation", "${route.generationBootEpoch}:${route.generationRouteSeq}")
        ValueRow("FD / Rank", "${route.feasibleDistance} / ${route.guardRank}")
        ValueRow("Primary pathTag", hex32(route.primaryPathTag))
        ValueRow("Backup pathTag", hex32(route.backupPathTag))
        ValueRow("Masks", "P ${hex32(route.primaryInternalMask)} · G2 ${hex32(route.backupInternalMask)}")
    }
}

@Composable
private fun FaultTab(
    state: VanguardControlUiState,
    selected: NodeId?,
    setSelected: (NodeId) -> Unit,
    duration: Long,
    setDuration: (Long) -> Unit,
    apply: (NodeId, LabLinkPreset, Long) -> Unit,
    inject: (NodeId, Long) -> Unit,
) {
    val peers = state.nodes.filter { it.id != state.session?.localNodeIdentity?.nodeId }.map { it.id }.distinct()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TechnicalCard("Deterministic Fault Lab") {
                Text("Правило действует на локальном узле в направлении к выбранному peer. Для симметричного A↔B теста примени тот же preset после подключения к второй стороне.", color = SecureMeshColors.TextSecondary)
                NodePicker("Peer", selected, peers, setSelected)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5_000L to "5с", 30_000L to "30с", 120_000L to "120с", 0xFFFF_FFFFL to "∞").forEach { (ms,label) ->
                        FilterChip(selected = duration == ms, onClick = { setDuration(ms) }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selected?.let { apply(it, LabLinkPreset.SOFT_WEAK, duration) } }, enabled = selected != null && !state.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Warning, contentColor = Color(0xFF261800))) { Text("Soft weak") }
                    Button(onClick = { selected?.let { apply(it, LabLinkPreset.VERY_WEAK, duration) } }, enabled = selected != null && !state.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Violet)) { Text("Very weak") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selected?.let { apply(it, LabLinkPreset.BLOCK, duration) } }, enabled = selected != null && !state.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Critical)) { Text("BLOCK") }
                    OutlinedButton(onClick = { selected?.let { apply(it, LabLinkPreset.CLEAR, 0) } }, enabled = selected != null && !state.busy, modifier = Modifier.weight(1f)) { Text("Clear") }
                }
                TextButton(onClick = { selected?.let { inject(it, duration) } }, enabled = selected != null && !state.busy) { Text("Legacy InjectLinkFailure") }
            }
        }
        item {
            TechnicalCard("Счётчики Fault Lab") {
                ValueRow("RX drops", state.diagnostics?.labFaultRxDrops?.toString() ?: "—")
                ValueRow("TX drops", state.diagnostics?.labFaultTxDrops?.toString() ?: "—")
                ValueRow("Active rules", state.diagnostics?.activeLabFaults?.toString() ?: state.labPolicies.size.toString())
            }
        }
        if (state.labPolicies.isEmpty()) item { EmptyState("Активных правил нет", "Физическая топология используется без лабораторного overlay.") }
        else items(state.labPolicies, key = { it.peerNodeId }) { policy ->
            TechnicalCard(policy.peerNodeId) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (policy.block) StatusChip("BLOCK", SecureMeshColors.Critical)
                    if (policy.metricOverride) StatusChip("METRIC", SecureMeshColors.Warning)
                }
                ValueRow("Reliability", "%.1f%%".format(Locale.US, policy.reliability * 100))
                ValueRow("ECA", "%.2f".format(Locale.US, policy.eca))
                ValueRow("Осталось", if (policy.manual) "manual" else "${policy.remainingMs/1000.0}s")
            }
        }
    }
}

@Composable
private fun ManifestTab(state: VanguardControlUiState, onConfigure: () -> Unit) {
    val manifest = state.manifest
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TechnicalCard("Network Manifest") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusChip(if (manifest?.valid == true) "VALID" else "NOT SET", if (manifest?.valid == true) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                    if (state.manifestAvailable) Button(onClick = onConfigure, enabled = !state.busy) { Text("Настроить") }
                }
                ValueRow("Epoch", manifest?.networkEpoch?.toString() ?: "—")
                ValueRow("Digest", manifest?.digest?.let(::hex32) ?: "—")
                ValueRow("Узлов", manifest?.entries?.size?.toString() ?: "0")
                Text("Для exact G2 все три узла должны иметь одинаковые epoch + digest. Приложение меняет manifest только текущего BLE-узла — затем подключись к остальным и запиши тот же набор.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        manifest?.entries?.let { entries -> items(entries, key = { it.slot }) { item ->
            Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SLOT ${item.slot}", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.Bold)
                    Text(item.nodeId, fontFamily = FontFamily.Monospace)
                }
            }
        } }
    }
}

@Composable
private fun OledTab(state: VanguardControlUiState, action: (DeviceUiAction) -> Unit, refresh: () -> Unit) {
    val ui = state.deviceUi
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.uiOsAvailable) item { EmptyState("UI OS недоступен", "Прошивка не объявила capability UI_OS.") }
        else {
            item {
                TechnicalCard("Физический OLED") {
                    if (ui == null) Text("Состояние ещё не синхронизировано", color = SecureMeshColors.Muted)
                    else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatusChip("UI v${ui.modelVersion}", SecureMeshColors.Cyan)
                            StatusChip(if (ui.oledReady) "OLED LIVE" else "OLED WAIT", if (ui.oledReady) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                        }
                        ValueRow("Сцена", ui.scene.label)
                        ValueRow("Меню", ui.menu.label)
                        ValueRow("Раздел", ui.feature.label)
                        ValueRow("Cursor", "menu=${ui.menuIndex} msg=${ui.messageIndex} neigh=${ui.neighborIndex} route=${ui.routeIndex}")
                    }
                    OutlinedButton(onClick = refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(7.dp)); Text("Считать UI state") }
                }
            }
            item { OledRemote(state.busy, action) }
        }
    }
}

@Composable
private fun OledRemote(busy: Boolean, action: (DeviceUiAction) -> Unit) {
    Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.extraLarge, border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha=.22f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ПУЛЬТ ЭКРАНА", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.ExtraBold)
            RemoteKey(Icons.Rounded.KeyboardArrowUp, busy) { action(DeviceUiAction.UP) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteKey(Icons.Rounded.ArrowBack, busy) { action(DeviceUiAction.BACK) }
                RemoteKey(Icons.Rounded.Check, busy, true) { action(DeviceUiAction.SELECT) }
                RemoteKey(Icons.Rounded.Home, busy) { action(DeviceUiAction.HOME) }
            }
            RemoteKey(Icons.Rounded.KeyboardArrowDown, busy) { action(DeviceUiAction.DOWN) }
        }
    }
}

@Composable
private fun RemoteKey(icon: androidx.compose.ui.graphics.vector.ImageVector, busy: Boolean, main: Boolean = false, click: () -> Unit) {
    PressScaleSurface(onClick = click, enabled = !busy, modifier = Modifier.size(if (main) 78.dp else 68.dp), shape = RoundedCornerShape(24.dp), color = if (main) SecureMeshColors.Cyan.copy(alpha=.18f) else SecureMeshColors.SurfaceBright, border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha=if(main).55f else .20f))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if(main) SecureMeshColors.CyanHot else SecureMeshColors.TextSecondary, modifier = Modifier.size(if(main)36.dp else 30.dp)) }
    }
}

@Composable
private fun ManifestDialog(state: VanguardControlUiState, onDismiss: () -> Unit, apply: (Long, List<NodeId>) -> Unit) {
    val local = state.session?.localNodeIdentity?.nodeId.orEmpty()
    val suggested = (listOf(local) + state.knownNodeIds).filter { it.isNotBlank() }.distinct().take(16)
    var epochText by remember { mutableStateOf(state.manifest?.networkEpoch?.takeIf { it != 0L }?.toString() ?: ((System.currentTimeMillis()/1000L) and 0xFFFF_FFFFL).toString()) }
    var nodesText by remember { mutableStateOf(suggested.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Записать Manifest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Один и тот же epoch и список NodeID надо записать на A, B и C.", color = SecureMeshColors.TextSecondary)
                OutlinedTextField(epochText, { epochText = it.filter(Char::isDigit) }, label = { Text("Network epoch (u32)") }, singleLine = true)
                OutlinedTextField(nodesText, { nodesText = it }, label = { Text("NodeID — по одному на строку") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val epoch = epochText.toLongOrNull() ?: return@TextButton
                val ids = nodesText.lines().map { it.trim().uppercase().removePrefix("0X") }.filter { it.isNotBlank() }.distinct()
                apply(epoch, ids)
            }) { Text("Записать в текущий узел") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NodePicker(label: String, selected: NodeId?, options: List<NodeId>, set: (NodeId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(value = selected ?: "", onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { id -> DropdownMenuItem(text = { Text(id, fontFamily = FontFamily.Monospace) }, onClick = { set(id); expanded = false }) }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SecureMeshColors.Muted, modifier = Modifier.weight(1f))
        Text(value, color = SecureMeshColors.TextSecondary, fontWeight = FontWeight.SemiBold)
    }
}

private fun hex32(value: Long): String = "0x" + value.toString(16).uppercase(Locale.US).padStart(8, '0')
