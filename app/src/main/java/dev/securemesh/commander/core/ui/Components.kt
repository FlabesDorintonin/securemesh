package dev.securemesh.commander.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.domain.model.*

@Composable
fun MeshBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        SecureMeshColors.GraphiteSoft,
                        SecureMeshColors.Graphite,
                        Color(0xFF04101A),
                    ),
                ),
            )
            .background(
                Brush.linearGradient(
                    listOf(
                        SecureMeshColors.Cyan.copy(alpha = .08f),
                        Color.Transparent,
                        SecureMeshColors.Violet.copy(alpha = .055f),
                    ),
                ),
            ),
        content = content,
    )
}

@Composable
fun TechnicalCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.animateContentSize(animationSpec = tween(220)),
        colors = CardDefaults.cardColors(containerColor = SecureMeshColors.SurfaceHigh.copy(alpha = .94f)),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = 0.82f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = SecureMeshColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn(tween(170)) + slideInVertically(tween(170)) { it / 5 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 5 })
            },
            label = "metric-value",
        ) { current ->
            Text(
                current,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = SecureMeshColors.Cyan,
) {
    Surface(
        modifier = modifier.animateContentSize(animationSpec = tween(220)),
        color = SecureMeshColors.SurfaceHigh,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, accent.copy(alpha = .22f)),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(6.dp).background(accent, CircleShape))
                Text(label, style = MaterialTheme.typography.labelMedium, color = SecureMeshColors.TextSecondary)
            }
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "metric-tile-value",
            ) { current ->
                Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
            }
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val animatedColor by animateColorAsState(color, tween(220), label = "status-chip-color")
    Row(
        modifier = modifier
            .background(animatedColor.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, animatedColor.copy(alpha = .22f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(animatedColor, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = animatedColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MeshAvatar(
    name: String,
    online: Boolean? = null,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    accent: Color = SecureMeshColors.Cyan,
) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifBlank { "SM" }
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = .34f),
                            SecureMeshColors.Blue.copy(alpha = .20f),
                        ),
                    ),
                )
                .border(1.dp, accent.copy(alpha = .42f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = SecureMeshColors.Text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (online != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * .29f).dp)
                    .background(SecureMeshColors.Graphite, CircleShape)
                    .padding(2.dp)
                    .background(if (online) SecureMeshColors.Healthy else SecureMeshColors.Muted, CircleShape),
            )
        }
    }
}

@Composable
fun MenuRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        color = SecureMeshColors.SurfaceHigh,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .78f)),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                SecureMeshColors.Cyan.copy(alpha = .20f),
                                SecureMeshColors.Blue.copy(alpha = .13f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = SecureMeshColors.CyanHot, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = SecureMeshColors.Text)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecureMeshColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = SecureMeshColors.Cyan.copy(alpha = .70f))
        }
    }
}

@Composable
fun VibrantPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, tween(90), label = "primary-button-scale")

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = SecureMeshColors.Cyan,
            contentColor = Color(0xFF001E28),
            disabledContainerColor = SecureMeshColors.SurfaceBright,
            disabledContentColor = SecureMeshColors.Muted,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 7.dp, pressedElevation = 2.dp),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ConnectionBanner(state: MeshConnectionState, modifier: Modifier = Modifier) {
    val presentation = when (state) {
        is MeshConnectionState.Connected -> if (state.secureSession == SecureSessionState.ESTABLISHED) {
            Triple("Защищённая сессия активна", "Телефон подключён к локальному узлу SecureMesh", SecureMeshColors.Healthy)
        } else {
            Triple("BLE подключён", "Протокол SecureMesh ещё не подтверждён", SecureMeshColors.Warning)
        }
        is MeshConnectionState.Connecting -> Triple("Подключение…", "Устанавливаем BLE-соединение", SecureMeshColors.Cyan)
        is MeshConnectionState.DiscoveringServices -> Triple("Проверяем сервисы…", "Читаем GATT-сервисы устройства", SecureMeshColors.Cyan)
        is MeshConnectionState.IdentifyingSecureMesh -> Triple("Определяем узел…", "Проверяем идентичность SecureMesh", SecureMeshColors.Cyan)
        is MeshConnectionState.SyncingSession -> Triple("Синхронизация…", "Получаем возможности и права сессии", SecureMeshColors.Cyan)
        is MeshConnectionState.Reconnecting -> Triple("Переподключение", "Попытка ${state.attempt} из 3", SecureMeshColors.Warning)
        is MeshConnectionState.Error -> Triple("Ошибка соединения", localizedError(state.error.userMessage) ?: "Неизвестная ошибка", SecureMeshColors.Critical)
        MeshConnectionState.BluetoothDisabled -> Triple("Bluetooth выключен", "Включи Bluetooth для поиска узлов", SecureMeshColors.Warning)
        MeshConnectionState.BluetoothUnavailable -> Triple("Bluetooth недоступен", "На устройстве не найден BLE-адаптер", SecureMeshColors.Critical)
        is MeshConnectionState.PermissionRequired -> Triple("Нужно разрешение Bluetooth", "Android требует доступ к ближайшим устройствам", SecureMeshColors.Warning)
        is MeshConnectionState.Disconnected -> Triple("Отключено", localizedTechnicalText(state.reason), SecureMeshColors.Muted)
        is MeshConnectionState.Scanning -> Triple("Идёт поиск узлов", "Сканирование BLE ограничено по времени", SecureMeshColors.Cyan)
        is MeshConnectionState.DeviceFound -> Triple("Устройства найдены", "Выбери узел для подключения", SecureMeshColors.Cyan)
        else -> Triple("Не подключено", "Подключи локальный узел или запусти демо", SecureMeshColors.Muted)
    }
    val active = state is MeshConnectionState.Connected || state is MeshConnectionState.Connecting ||
        state is MeshConnectionState.Scanning || state is MeshConnectionState.Reconnecting

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
        color = presentation.third.copy(alpha = .105f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, presentation.third.copy(alpha = .30f)),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulseDot(active = active, color = presentation.third)
            AnimatedContent(
                targetState = presentation.first to presentation.second,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "connection-banner",
            ) { (title, subtitle) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = presentation.third)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SecureMeshColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun PulseDot(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "connection-pulse")
    val pulse by transition.animateFloat(
        initialValue = .42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(820), repeatMode = RepeatMode.Reverse),
        label = "connection-pulse-alpha",
    )
    val halo by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(animation = tween(820), repeatMode = RepeatMode.Reverse),
        label = "connection-pulse-scale",
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer {
                    scaleX = if (active) halo else 1f
                    scaleY = if (active) halo else 1f
                }
                .alpha(if (active) pulse * .42f else .18f)
                .background(color, CircleShape),
        )
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

fun linkQualityColor(quality: LinkQuality): Color = when (quality) {
    LinkQuality.EXCELLENT, LinkQuality.GOOD -> SecureMeshColors.Healthy
    LinkQuality.DEGRADED -> SecureMeshColors.Warning
    LinkQuality.CRITICAL -> SecureMeshColors.Critical
    LinkQuality.UNKNOWN -> SecureMeshColors.Muted
}

@Composable
fun EmptyState(title: String, detail: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            SecureMeshColors.Cyan.copy(alpha = .20f),
                            SecureMeshColors.Violet.copy(alpha = .12f),
                        ),
                    ),
                )
                .border(1.dp, SecureMeshColors.Cyan.copy(alpha = .28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("SM", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.ExtraBold)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SecureMeshColors.Text)
        Text(detail, color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            VibrantPrimaryButton(actionLabel, onAction, Modifier.padding(top = 4.dp))
        }
    }
}
