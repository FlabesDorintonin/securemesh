@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.securemesh.commander.feature.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.map.*
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.feature.network.NetworkViewModel
import dev.securemesh.commander.feature.nodes.ContactEditorDialog
import kotlin.math.max

@Composable
fun MapScreen(
    viewModel: NetworkViewModel,
    mapManager: OfflineMapManager,
    provider: MeshMapProvider = MapLibreMeshMapProvider,
    onNode: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val actionBusy by viewModel.actionBusy.collectAsStateWithLifecycle()
    val mapState by mapManager.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(false) }
    var threeD by rememberSaveable { mutableStateOf(true) }
    var fitAllRequest by remember { mutableIntStateOf(0) }
    var confirmSos by remember { mutableStateOf(false) }
    var editContactId by remember { mutableStateOf<String?>(null) }
    var showMapManager by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val positionedNodes = if (state.canMap) state.mapNodes.filter { it.position?.let(::hasUsableCoordinate) == true } else emptyList()
    val sosNodeId = state.activeSos?.nodeId
    val points = buildList {
        positionedNodes.forEach { node ->
            node.position?.let { position ->
                add(
                    MapPoint(
                        id = node.id,
                        label = state.contactProfiles[node.id]?.displayName(deviceDisplayName(node.name)) ?: deviceDisplayName(node.name),
                        latitude = position.latitude,
                        longitude = position.longitude,
                        kind = if (node.id == sosNodeId) MapPointKind.SOS else MapPointKind.NODE,
                        online = node.online,
                        freshFix = position.valid,
                    )
                )
            }
        }
        val sos = state.activeSos
        if (sos?.position?.let(::hasUsableCoordinate) == true && none { it.id == sos.nodeId }) {
            add(MapPoint(sos.nodeId, "SOS · ${sos.nodeId}", sos.position.latitude, sos.position.longitude, MapPointKind.SOS, true, sos.position.valid))
        }
    }

    val tracks = state.positionHistory
        .groupBy { it.nodeId }
        .mapValues { (_, history) ->
            history.asSequence()
                .filter { hasUsableCoordinate(it) }
                .sortedBy { it.timestampEpochMs }
                .takeLast(240)
                .map { MapCoordinate(it.latitude, it.longitude) }
                .toList()
        }
        .filterValues { it.size >= 2 }

    LaunchedEffect(points.map { it.id }) {
        if (selected != null && points.none { it.id == selected }) {
            selected = null
            follow = false
        }
    }
    LaunchedEffect(actionMessage) {
        actionMessage?.let { snackbar.showSnackbar(it); viewModel.clearActionMessage() }
    }
    LaunchedEffect(mapState.notice) {
        mapState.notice?.let { snackbar.showSnackbar(it); mapManager.clearNotice() }
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Карта сети", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text(
                            when {
                                points.isEmpty() -> "Позиции узлов появятся автоматически"
                                tracks.isEmpty() -> "На карте ${points.size} ${nodeWord(points.size)}"
                                else -> "На карте ${points.size} ${nodeWord(points.size)} · ${tracks.size} ${trackWord(tracks.size)}"
                            },
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.activeSos != null) StatusChip("SOS", SecureMeshColors.Critical)
                    else StatusChip(if (mapState.activePack != null) "КАРТА ГОТОВА" else "БЕЗ КАРТЫ", if (mapState.activePack != null) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { selected = null; follow = false; fitAllRequest++ },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 9.dp),
                        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .28f)),
                    ) {
                        Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Вся сеть")
                    }
                    FilledTonalButton(onClick = { showMapManager = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Rounded.Map, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Карты")
                    }
                    FilterChip(selected = threeD, onClick = { threeD = !threeD }, label = { Text("3D") })
                    FilterChip(
                        selected = follow,
                        onClick = { follow = !follow },
                        label = { Text("Следить") },
                        leadingIcon = { Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp)) },
                        enabled = selected != null,
                    )
                    FilledTonalIconButton(
                        onClick = { confirmSos = true },
                        enabled = state.canRaiseSos && !actionBusy,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SecureMeshColors.Critical.copy(alpha = .18f), contentColor = SecureMeshColors.Critical),
                    ) { Icon(Icons.Rounded.Warning, contentDescription = "SOS") }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = MaterialTheme.shapes.large,
                    color = SecureMeshColors.Surface,
                    border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f)),
                    tonalElevation = 2.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        provider.Render(
                            state = MapRenderState(
                                points = points,
                                selectedPointId = selected,
                                followSelected = follow,
                                fitAllRequest = fitAllRequest,
                                tracks = tracks,
                                threeDimensional = threeD,
                                basemapUri = mapState.activePack?.mapLibreUri,
                                basemapBounds = mapState.activePack?.bounds,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        ) { selected = it }

                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                            color = SecureMeshColors.SurfaceHigh.copy(alpha = .90f),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f)),
                        ) {
                            Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                Text(mapState.activePack?.displayName ?: "Карта местности не загружена", color = if (mapState.activePack != null) SecureMeshColors.CyanHot else SecureMeshColors.Warning, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(if (mapState.activePack != null) "работает без интернета" else "узлы всё равно видны по координатам", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (mapState.activePack == null) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center).padding(22.dp),
                                color = SecureMeshColors.SurfaceHigh.copy(alpha = .94f),
                                shape = MaterialTheme.shapes.large,
                                border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .28f)),
                            ) {
                                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.Map, null, tint = SecureMeshColors.Warning)
                                    Text("Добавь карту местности", fontWeight = FontWeight.Bold)
                                    Text("Скачай карту один раз или выбери файл с телефона. После установки сеть для просмотра не нужна.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { showMapManager = true }) { Text("Добавить карту") }
                                }
                            }
                        } else if (points.isEmpty()) {
                            Surface(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                color = SecureMeshColors.SurfaceHigh.copy(alpha = .92f),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text("Пока нет координат узлов. Выйди на открытое место — позиции появятся автоматически.", modifier = Modifier.padding(10.dp), color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = selected != null) {
                    selected?.let { id ->
                        val node = state.mapNodes.firstOrNull { it.id == id }
                        val position = node?.position ?: state.activeSos?.takeIf { it.nodeId == id }?.position
                        if (position != null) {
                            NodeMapActionCard(
                                node = node,
                                nodeId = id,
                                position = position,
                                sos = state.activeSos?.takeIf { it.nodeId == id },
                                contact = state.contactProfiles[id],
                                canMessage = state.canSendMessages,
                                canCommand = state.canSendCommands,
                                busy = actionBusy,
                                onMessage = { onMessage(id) },
                                onCommand = { viewModel.sendCommand(id, it) },
                                onOpen = { onNode(id) },
                                onEditContact = { editContactId = id },
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }

    if (showMapManager) OfflineMapManagerSheet(mapManager, mapState) { showMapManager = false }

    editContactId?.let { nodeId ->
        ContactEditorDialog(
            nodeId = nodeId,
            initial = state.contactProfiles[nodeId],
            onSave = { alias, note, pinned -> viewModel.saveContact(nodeId, alias, note, pinned) },
            onClear = { viewModel.clearContact(nodeId) },
            onDismiss = { editContactId = null },
        )
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            icon = { Icon(Icons.Rounded.Warning, null, tint = SecureMeshColors.Critical) },
            title = { Text("Отправить SOS?") },
            text = { Text("Тревога уйдёт в mesh вместе с текущим или последним известным местоположением.") },
            confirmButton = { Button(onClick = { confirmSos = false; viewModel.raiseSos() }, colors = ButtonDefaults.buttonColors(containerColor = SecureMeshColors.Critical, contentColor = Color.White)) { Text("ОТПРАВИТЬ SOS") } },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun OfflineMapManagerSheet(
    manager: OfflineMapManager,
    state: OfflineMapState,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var showUrl by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(manager::importFromUri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Офлайн-карты", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("Карта хранится на телефоне. После установки её можно открыть без мобильной сети и Wi‑Fi.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)

            state.transfer?.let { transfer ->
                Surface(color = SecureMeshColors.Cyan.copy(alpha = .08f), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (transfer.kind == OfflineMapTransferKind.DOWNLOAD) "Скачиваю ${transfer.name}" else "Добавляю ${transfer.name}", fontWeight = FontWeight.Bold)
                        transfer.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            buildString {
                                append(transfer.bytesDone.humanFileSize())
                                transfer.bytesTotal?.let { append(" из ${it.humanFileSize()}") }
                            },
                            color = SecureMeshColors.Muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(onClick = manager::cancelTransfer) { Text("Отменить") }
                    }
                }
            }

            if (state.packs.isEmpty()) {
                Text("На телефоне пока нет карт.", color = SecureMeshColors.Muted)
            } else {
                state.packs.forEach { pack ->
                    val active = pack.id == state.activePackId
                    Surface(
                        color = if (active) SecureMeshColors.Cyan.copy(alpha = .08f) else SecureMeshColors.SurfaceHigh,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, (if (active) SecureMeshColors.Cyan else SecureMeshColors.Divider).copy(alpha = .24f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Map, null, tint = if (active) SecureMeshColors.CyanHot else SecureMeshColors.Muted)
                            Column(Modifier.weight(1f)) {
                                Text(pack.displayName, fontWeight = FontWeight.Bold)
                                Text("${pack.sizeBytes.humanFileSize()} · масштаб ${pack.minZoom}–${pack.maxZoom}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            if (active) StatusChip("ИСПОЛЬЗУЕТСЯ", SecureMeshColors.Healthy)
                            else TextButton(onClick = { manager.select(pack.id) }) { Text("Выбрать") }
                            IconButton(onClick = { manager.delete(pack.id) }) { Icon(Icons.Rounded.Delete, contentDescription = "Удалить карту") }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.transfer == null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Выбрать файл")
                }
                OutlinedButton(onClick = { showUrl = !showUrl }, enabled = state.transfer == null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Скачать")
                }
            }

            AnimatedVisibility(showUrl) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Ссылка на карту", fontWeight = FontWeight.Bold)
                    Text("Поддерживается защищённая HTTPS-ссылка на векторную карту PMTiles.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://…/region.pmtiles") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Button(onClick = { manager.startDownload(url) }, enabled = url.isNotBlank() && state.transfer == null, modifier = Modifier.fillMaxWidth()) { Text("Начать скачивание") }
                }
            }
        }
    }
}

@Composable
private fun NodeMapActionCard(
    node: MeshNode?,
    nodeId: String,
    position: NodePosition,
    sos: SosAlert?,
    contact: ContactProfile?,
    canMessage: Boolean,
    canCommand: Boolean,
    busy: Boolean,
    onMessage: () -> Unit,
    onCommand: (CommandNoticeKind) -> Unit,
    onOpen: () -> Unit,
    onEditContact: () -> Unit,
) {
    Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, (if (sos != null) SecureMeshColors.Critical else SecureMeshColors.Cyan).copy(alpha = .30f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fallbackName = deviceDisplayName(node?.name ?: "Узел $nodeId")
            val displayName = contact?.displayName(fallbackName) ?: fallbackName
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MeshAvatar(displayName, node?.online ?: true, size = 43.dp)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName, fontWeight = FontWeight.Bold)
                        if (sos != null) StatusChip("SOS", SecureMeshColors.Critical)
                        if (contact?.notePinned == true) Icon(Icons.Rounded.PushPin, null, tint = SecureMeshColors.CyanHot, modifier = Modifier.size(15.dp))
                    }
                    Text("${coordinate(position.latitude)}, ${coordinate(position.longitude)}", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.bodySmall)
                    Text(positionStatusText(position), color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
                Row {
                    IconButton(onClick = onEditContact) { Icon(Icons.Rounded.Edit, contentDescription = "Изменить контакт") }
                    TextButton(onClick = onOpen) { Text("Профиль") }
                }
            }
            contact?.note?.takeIf { contact.notePinned }?.let { note ->
                Surface(color = SecureMeshColors.Cyan.copy(alpha = .08f), shape = MaterialTheme.shapes.medium) {
                    Text(note, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = SecureMeshColors.TextSecondary, maxLines = 2)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onMessage, enabled = canMessage && !busy, modifier = Modifier.weight(1f)) { Text("Сообщение") }
                OutlinedButton(onClick = { onCommand(CommandNoticeKind.CHECK_IN) }, enabled = canCommand && !busy, modifier = Modifier.weight(1f)) { Text("Как дела?") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onCommand(CommandNoticeKind.RETURN) }, enabled = canCommand && !busy, modifier = Modifier.weight(1f)) { Text("Вернись") }
                OutlinedButton(onClick = { onCommand(CommandNoticeKind.HOLD) }, enabled = canCommand && !busy, modifier = Modifier.weight(1f)) { Text("Оставайся") }
            }
        }
    }
}

private fun positionStatusText(position: NodePosition): String {
    val ageMs = (System.currentTimeMillis() - position.timestampEpochMs).coerceAtLeast(0L)
    return if (position.valid && ageMs <= 15_000L) "Местоположение обновлено сейчас"
    else "Последняя известная позиция · ${humanAge(ageMs)} назад"
}

private fun humanAge(ageMs: Long): String {
    val seconds = max(1L, ageMs / 1000L)
    return when {
        seconds < 60 -> "$seconds сек"
        seconds < 3600 -> "${seconds / 60} мин"
        else -> "${seconds / 3600} ч"
    }
}

private fun nodeWord(count: Int): String = if (count % 10 == 1 && count % 100 != 11) "узел" else "узла"
private fun trackWord(count: Int): String = if (count % 10 == 1 && count % 100 != 11) "маршрут" else "маршрута"

private fun hasUsableCoordinate(position: NodePosition): Boolean =
    position.latitude.isFinite() && position.longitude.isFinite() &&
        position.latitude in -90.0..90.0 && position.longitude in -180.0..180.0 &&
        !(position.latitude == 0.0 && position.longitude == 0.0)

private fun <T> Sequence<T>.takeLast(count: Int): List<T> {
    if (count <= 0) return emptyList()
    val buffer = ArrayDeque<T>(count)
    for (item in this) {
        if (buffer.size == count) buffer.removeFirst()
        buffer.addLast(item)
    }
    return buffer.toList()
}
