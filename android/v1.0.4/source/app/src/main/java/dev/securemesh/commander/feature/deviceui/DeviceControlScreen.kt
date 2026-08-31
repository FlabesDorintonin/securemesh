package dev.securemesh.commander.feature.deviceui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*
import kotlinx.coroutines.delay

private const val OLED_MIRROR_POLL_INTERVAL_MS = 800L

@Composable
fun DeviceControlScreen(viewModel: DeviceControlViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var requestedInitialState by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(state.allowed, state.device) {
        if (state.allowed && state.device == null && !requestedInitialState) {
            requestedInitialState = true
            viewModel.refresh()
        }
    }

    LaunchedEffect(state.allowed, state.exactMirrorAvailable) {
        if (!state.allowed || !state.exactMirrorAvailable) return@LaunchedEffect
        while (true) {
            viewModel.refreshMirror()
            delay(OLED_MIRROR_POLL_INTERVAL_MS)
        }
    }

    MeshBackdrop(Modifier.fillMaxSize()) {
        if (!state.allowed) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Surface(
                    color = SecureMeshColors.SurfaceHigh.copy(alpha = .94f),
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .30f)),
                ) {
                    Column(
                        Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusChip("OLED CONTROL", SecureMeshColors.Warning)
                        Text("Пульт пока недоступен", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "Нужна защищённая SecureMesh-сессия и прошивка с возможностью UI OS. После подключения этот экран станет живым пультом физического OLED.",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            return@MeshBackdrop
        }

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StaggeredReveal(entered, 0) {
                    DeviceControlHeader(
                        state = state,
                        onRefresh = viewModel::refresh,
                    )
                }
            }

            val errorMessage = state.error
            if (errorMessage != null) {
                item {
                    Surface(
                        color = SecureMeshColors.Warning.copy(alpha = .10f),
                        border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .34f)),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Команда не выполнена", fontWeight = FontWeight.Bold, color = SecureMeshColors.Warning)
                                Text(errorMessage, color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = viewModel::clearError) { Text("Закрыть") }
                        }
                    }
                }
            }

            val device = state.device
            if (device == null) {
                item {
                    StaggeredReveal(entered, 70) {
                        OledLoadingCard(onRefresh = viewModel::refresh, busy = state.busy)
                    }
                }
            } else {
                item {
                    StaggeredReveal(entered, 70) {
                        OledLivePreview(device, state.oledFramebuffer, state.exactMirrorAvailable)
                    }
                }

                item {
                    StaggeredReveal(entered, 115) {
                        DeviceRemote(
                            busy = state.busy,
                            action = viewModel::action,
                        )
                    }
                }

                if (device.plannedFeature) {
                    item {
                        Surface(
                            color = SecureMeshColors.Violet.copy(alpha = .09f),
                            border = BorderStroke(1.dp, SecureMeshColors.Violet.copy(alpha = .28f)),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Раздел ещё не активен", fontWeight = FontWeight.Bold, color = SecureMeshColors.Violet)
                                Text(
                                    "Прошивка сама пометила «${device.feature.label}» как запланированную функцию. Приложение не подменяет её фиктивными действиями.",
                                    color = SecureMeshColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item {
                    StaggeredReveal(entered, 155) {
                        LiveTelemetryCard(device)
                    }
                }
            }

            item {
                TechnicalCard("Защищённый канал") {
                    DeviceValueRow("Node ID", state.session?.localNodeIdentity?.nodeId ?: "—")
                    DeviceValueRow("Прошивка", state.session?.firmwareVersion ?: "—")
                    DeviceValueRow("BLE Protocol", state.session?.protocolVersion?.let { "v$it" } ?: "—")
                    DeviceValueRow("Сессия", "Аутентифицирована")
                }
            }

            item {
                Text(
                    if (state.exactMirrorAvailable) "Кнопки используют UI_ACTION, а мини-экран получает точный 128×64 framebuffer OLED. Радио-пакеты этим пультом не создаются." else "Кнопки используют UI_ACTION. Эта прошивка отдаёт только GET_UI_STATE, поэтому до обновления firmware мини-экран работает в режиме STATE SYNC, а не как пиксельная копия OLED.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceControlHeader(state: DeviceControlUiState, onRefresh: () -> Unit) {
    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .90f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .22f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(if (state.exactMirrorAvailable) "OLED MIRROR" else "OLED CONTROL", SecureMeshColors.CyanHot)
                    if (state.device?.oledReady == true) StatusChip("SYNC", SecureMeshColors.Healthy)
                }
                Text("Устройство", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(if (state.exactMirrorAvailable) "Точное зеркало 128×64 + аппаратные кнопки" else "Пульт OLED; точное зеркало включится после firmware framebuffer extension", color = SecureMeshColors.TextSecondary)
            }
            FilledTonalIconButton(
                onClick = onRefresh,
                enabled = !state.busy,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = SecureMeshColors.Cyan.copy(alpha = .12f),
                    contentColor = SecureMeshColors.CyanHot,
                ),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
            }
        }
    }
}

@Composable
private fun OledLoadingCard(onRefresh: () -> Unit, busy: Boolean) {
    Surface(
        color = Color(0xFF02080B),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .30f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(15.dp))
                    .background(Color.Black)
                    .border(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(150.dp),
                        color = SecureMeshColors.CyanHot,
                        trackColor = SecureMeshColors.SurfaceBright,
                    )
                    Text("GET_UI_STATE", color = SecureMeshColors.CyanHot, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("Синхронизирую OLED с узлом…", color = SecureMeshColors.TextSecondary)
            OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Повторить") }
        }
    }
}

@Composable
private fun OledLivePreview(
    device: DeviceUiState,
    frame: OledFramebufferSnapshot?,
    exactMirrorAvailable: Boolean,
) {
    val title = device.feature.takeUnless { it == DeviceUiFeature.NONE }?.label ?: device.menu.label
    val secondary = when (device.scene) {
        DeviceUiScene.HOME -> "NODE ${device.localNodeId}"
        DeviceUiScene.MENU -> device.menu.label.uppercase()
        DeviceUiScene.FEATURE -> device.feature.label.uppercase()
        DeviceUiScene.UNKNOWN -> "UI STATE ${device.rawScene}"
    }
    val exact = exactMirrorAvailable && frame != null

    Surface(
        color = Color(0xFF02080B),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, if (device.oledReady) SecureMeshColors.Healthy.copy(alpha = .35f) else SecureMeshColors.Warning.copy(alpha = .35f)),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("OLED 128×64", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (exact) SecureMeshColors.Healthy else SecureMeshColors.Warning, CircleShape))
                    Text(
                        if (exact) "PIXEL MIRROR" else "STATE SYNC",
                        color = if (exact) SecureMeshColors.Healthy else SecureMeshColors.Warning,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Box(
                Modifier.fillMaxWidth().aspectRatio(2f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.Black)
                    .border(1.dp, SecureMeshColors.Cyan.copy(alpha = .24f), RoundedCornerShape(15.dp)),
            ) {
                if (exact) {
                    OledFramebufferCanvas(requireNotNull(frame))
                } else {
                    Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("SECUREMESH", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                            Text("BLE ${device.bleState}", color = if (device.bleProtocolReady) SecureMeshColors.Healthy else SecureMeshColors.Warning, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                            Text(secondary, color = SecureMeshColors.CyanHot.copy(alpha = .82f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("N:${device.neighborCount}  R:${device.routeCount}", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            Text(if (device.hasUnread) "MSG:${device.unreadCount}" else "READY", color = if (device.hasUnread) SecureMeshColors.Warning else SecureMeshColors.Healthy, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (exact) {
                Text(
                    "Снимок #${frame!!.snapshotId}: показаны те же 8192 монохромных пикселей, которые находятся в буфере SSD1306 на узле.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!exactMirrorAvailable) {
                Text(
                    "Текущая firmware не объявляет OLED_FRAMEBUFFER. Управление полностью работает, но изображение ниже реконструируется из UI state.",
                    color = SecureMeshColors.Warning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatus("Сцена", device.scene.label, SecureMeshColors.CyanHot, Modifier.weight(1f))
                MiniStatus("Меню", "${device.menuIndex + 1}", SecureMeshColors.Blue, Modifier.weight(1f))
                MiniStatus("Глубина", device.navigationDepth.toString(), SecureMeshColors.Violet, Modifier.weight(1f))
            }
            AnimatedVisibility(device.toastVisible) {
                StatusChip("На OLED показано уведомление", SecureMeshColors.CyanHot)
            }
        }
    }
}

@Composable
private fun OledFramebufferCanvas(frame: OledFramebufferSnapshot) {
    Canvas(Modifier.fillMaxSize()) {
        val pixelWidth = size.width / frame.width.toFloat()
        val pixelHeight = size.height / frame.height.toFloat()
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                if (!frame.pixelOn(x, y)) continue
                drawRect(
                    color = Color.White,
                    topLeft = Offset(x * pixelWidth, y * pixelHeight),
                    size = Size(pixelWidth, pixelHeight),
                )
            }
        }
    }
}

@Composable
private fun MiniStatus(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = .08f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = .18f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DeviceRemote(busy: Boolean, action: (DeviceUiAction) -> Unit) {
    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .90f),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .20f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Пульт OLED", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Text("Управление физическим меню", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                StatusChip(if (busy) "ОТПРАВКА" else "ГОТОВ", if (busy) SecureMeshColors.Warning else SecureMeshColors.Healthy)
            }

            RemoteButton(Icons.Rounded.KeyboardArrowUp, "Вверх", busy) { action(DeviceUiAction.UP) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteButton(Icons.Rounded.ArrowBack, "Назад", busy) { action(DeviceUiAction.BACK) }
                RemoteButton(Icons.Rounded.Check, "Выбрать", busy, emphasized = true) { action(DeviceUiAction.SELECT) }
                RemoteButton(Icons.Rounded.Home, "Домой", busy) { action(DeviceUiAction.HOME) }
            }
            RemoteButton(Icons.Rounded.KeyboardArrowDown, "Вниз", busy) { action(DeviceUiAction.DOWN) }

            if (busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = SecureMeshColors.CyanHot,
                    trackColor = SecureMeshColors.Divider,
                )
            }
        }
    }
}

@Composable
private fun RemoteButton(
    icon: ImageVector,
    description: String,
    busy: Boolean,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    PressScaleSurface(
        onClick = onClick,
        modifier = Modifier.size(if (emphasized) 76.dp else 66.dp),
        enabled = !busy,
        color = if (emphasized) SecureMeshColors.Cyan.copy(alpha = .18f) else SecureMeshColors.SurfaceBright.copy(alpha = .72f),
        border = BorderStroke(1.dp, if (emphasized) SecureMeshColors.CyanHot.copy(alpha = .55f) else SecureMeshColors.Cyan.copy(alpha = .20f)),
        shape = RoundedCornerShape(if (emphasized) 24.dp else 21.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (emphasized) SecureMeshColors.CyanHot else SecureMeshColors.TextSecondary,
                modifier = Modifier.size(if (emphasized) 34.dp else 30.dp),
            )
        }
    }
}

@Composable
private fun LiveTelemetryCard(device: DeviceUiState) {
    TechnicalCard("Живое состояние UI OS") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Входящие", device.inboxCount.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
            MetricTile("Новые", device.unreadCount.toString(), Modifier.weight(1f), if (device.unreadCount > 0) SecureMeshColors.Warning else SecureMeshColors.Healthy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Соседи", device.neighborCount.toString(), Modifier.weight(1f), SecureMeshColors.Blue)
            MetricTile("Маршруты", device.routeCount.toString(), Modifier.weight(1f), SecureMeshColors.Violet)
        }
        DeviceValueRow("Field Test", if (device.fieldTestRunning) "Выполняется" else "Остановлен")
        DeviceValueRow("BLE state", device.bleState.toString())
        DeviceValueRow("UI model", "v${device.modelVersion}")
    }
}

@Composable
private fun DeviceValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SecureMeshColors.Muted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, color = SecureMeshColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
