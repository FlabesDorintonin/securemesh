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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.DeviceUiAction
import dev.securemesh.commander.domain.model.DeviceUiFeature
import dev.securemesh.commander.domain.model.DeviceUiScene
import dev.securemesh.commander.domain.model.DeviceUiState
import dev.securemesh.commander.domain.model.OledFramebufferSnapshot
import kotlinx.coroutines.delay

private const val SCREEN_REFRESH_INTERVAL_MS = 1500L

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
            delay(SCREEN_REFRESH_INTERVAL_MS)
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
                        StatusChip("НЕТ ДОСТУПА", SecureMeshColors.Warning)
                        Text("Экран узла недоступен", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "Подключитесь к совместимому узлу. После защищённого подключения здесь появятся его экран и кнопки управления.",
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
                    ScreenHeader(
                        ready = state.device?.oledReady == true,
                        busy = state.busy,
                        onRefresh = viewModel::refresh,
                    )
                }
            }

            state.error?.let { errorMessage ->
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
                        ScreenLoadingCard(onRefresh = viewModel::refresh, busy = state.busy)
                    }
                }
            } else {
                item {
                    StaggeredReveal(entered, 70) {
                        LiveScreenCard(device, state.oledFramebuffer, state.exactMirrorAvailable)
                    }
                }
                item {
                    StaggeredReveal(entered, 115) {
                        ControlButtons(busy = state.busy, action = viewModel::action)
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
                                    "Узел пометил «${device.feature.label}» как будущую функцию. Приложение не показывает неработающие действия.",
                                    color = SecureMeshColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item {
                    StaggeredReveal(entered, 155) {
                        NodeStateCard(device)
                    }
                }
            }

            item {
                Surface(
                    color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .14f)),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Как это работает", fontWeight = FontWeight.Bold)
                        Text(
                            "Нажатия отправляются подключённому узлу по защищённому каналу и управляют только его экранным меню. Они не запускают отдельную радиопередачу.",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader(ready: Boolean, busy: Boolean, onRefresh: () -> Unit) {
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
                StatusChip(
                    if (ready) "ЭКРАН ДОСТУПЕН" else if (busy) "ОБНОВЛЕНИЕ" else "ПОДКЛЮЧЕНО",
                    if (ready) SecureMeshColors.Healthy else SecureMeshColors.CyanHot,
                )
                Text("Экран узла", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Живое изображение и кнопки управления", color = SecureMeshColors.TextSecondary)
            }
            FilledTonalIconButton(
                onClick = onRefresh,
                enabled = !busy,
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
private fun ScreenLoadingCard(onRefresh: () -> Unit, busy: Boolean) {
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
                    Text("Получаю экран…", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text("Синхронизирую состояние с узлом", color = SecureMeshColors.TextSecondary)
            OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Повторить") }
        }
    }
}

@Composable
private fun LiveScreenCard(
    device: DeviceUiState,
    frame: OledFramebufferSnapshot?,
    exactMirrorAvailable: Boolean,
) {
    val title = device.feature.takeUnless { it == DeviceUiFeature.NONE }?.label ?: device.menu.label
    val secondary = when (device.scene) {
        DeviceUiScene.HOME -> "Узел ${device.localNodeId}"
        DeviceUiScene.MENU -> device.menu.label
        DeviceUiScene.FEATURE -> device.feature.label
        DeviceUiScene.UNKNOWN -> "Состояние экрана"
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
                Text("Дисплей узла", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (exact) SecureMeshColors.Healthy else SecureMeshColors.Warning, CircleShape))
                    Text(
                        if (exact) "ТОЧНАЯ КОПИЯ" else "СОСТОЯНИЕ МЕНЮ",
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
                    ScreenPixelCanvas(requireNotNull(frame))
                } else {
                    Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SECUREMESH", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.ExtraBold)
                            Text(if (device.bleProtocolReady) "СВЯЗЬ ЕСТЬ" else "НЕТ СВЯЗИ", color = if (device.bleProtocolReady) SecureMeshColors.Healthy else SecureMeshColors.Warning)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(secondary, color = SecureMeshColors.CyanHot.copy(alpha = .82f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Соседи: ${device.neighborCount}", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall)
                            Text(if (device.hasUnread) "Новые: ${device.unreadCount}" else "Готов", color = if (device.hasUnread) SecureMeshColors.Warning else SecureMeshColors.Healthy, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Text(
                if (exact) "Приложение показывает точную копию изображения, которое сейчас находится на дисплее узла."
                else "Узел передаёт состояние меню. Кнопки работают, но изображение восстанавливается приложением без точной копии пикселей.",
                color = if (exact) SecureMeshColors.Muted else SecureMeshColors.Warning,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStatus("Раздел", device.scene.label, SecureMeshColors.CyanHot, Modifier.weight(1f))
                MiniStatus("Пункт", "${device.menuIndex + 1}", SecureMeshColors.Blue, Modifier.weight(1f))
                MiniStatus("Уровень", device.navigationDepth.toString(), SecureMeshColors.Violet, Modifier.weight(1f))
            }
            AnimatedVisibility(device.toastVisible) {
                StatusChip("На экране показано уведомление", SecureMeshColors.CyanHot)
            }
        }
    }
}

@Composable
private fun ScreenPixelCanvas(frame: OledFramebufferSnapshot) {
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
private fun ControlButtons(busy: Boolean, action: (DeviceUiAction) -> Unit) {
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
                    Text("Кнопки управления", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Text("Так же, как кнопки на самом узле", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                StatusChip(if (busy) "ВЫПОЛНЯЮ" else "ГОТОВО", if (busy) SecureMeshColors.Warning else SecureMeshColors.Healthy)
            }

            ControlButton(Icons.Rounded.KeyboardArrowUp, "Вверх", busy) { action(DeviceUiAction.UP) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(Icons.Rounded.ArrowBack, "Назад", busy) { action(DeviceUiAction.BACK) }
                ControlButton(Icons.Rounded.Check, "Выбрать", busy, emphasized = true) { action(DeviceUiAction.SELECT) }
                ControlButton(Icons.Rounded.Home, "Домой", busy) { action(DeviceUiAction.HOME) }
            }
            ControlButton(Icons.Rounded.KeyboardArrowDown, "Вниз", busy) { action(DeviceUiAction.DOWN) }

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
private fun ControlButton(
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
private fun NodeStateCard(device: DeviceUiState) {
    Surface(
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, SecureMeshColors.Blue.copy(alpha = .16f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Что сейчас на узле", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Входящие", device.inboxCount.toString(), Modifier.weight(1f), SecureMeshColors.Cyan)
                MetricTile("Новые", device.unreadCount.toString(), Modifier.weight(1f), if (device.unreadCount > 0) SecureMeshColors.Warning else SecureMeshColors.Healthy)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Соседи", device.neighborCount.toString(), Modifier.weight(1f), SecureMeshColors.Blue)
                MetricTile("Маршруты", device.routeCount.toString(), Modifier.weight(1f), SecureMeshColors.Violet)
            }
            Text(
                if (device.fieldTestRunning) "Сейчас выполняется испытание связи." else "Испытание связи сейчас не выполняется.",
                color = SecureMeshColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
