package dev.securemesh.commander.feature.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    onConnect: () -> Unit,
    onAutoConnected: (Boolean) -> Unit = {},
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val mode by viewModel.transportMode.collectAsStateWithLifecycle()

    LaunchedEffect(connection, session, mode) {
        if (mode == TransportMode.BLE && connection is MeshConnectionState.Connected) {
            onAutoConnected(session?.authenticationState == AuthenticationState.AUTHENTICATED)
        }
    }

    WelcomeContent(
        onConnect = { viewModel.prepareBle(onConnect) },
        reconnectText = (connection as? MeshConnectionState.Reconnecting)?.let {
            "Ищем доверенный узел ${it.identityHint} · попытка ${it.attempt} из 3"
        },
        onCancelReconnect = viewModel::cancelReconnect,
    )
}

@Composable
fun WelcomeContent(
    onConnect: () -> Unit,
    reconnectText: String? = null,
    onCancelReconnect: () -> Unit = {},
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(380)) + slideInVertically(tween(420)) { -it / 8 },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BrandPulse()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "SECUREMESH",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = SecureMeshColors.CyanHot,
                            )
                            Text(
                                "Автономная защищённая mesh-связь",
                                color = SecureMeshColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(430, delayMillis = 70)) + slideInVertically(tween(460, delayMillis = 70)) { it / 8 },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Связь вне инфраструктуры.",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SecureMeshColors.Text,
                        )
                        Text(
                            "Подключи локальный узел по Bluetooth. После проверки pairing, протокола и nodeId приложение откроет чаты, сеть, маршруты и полевые инструменты.",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(440, delayMillis = 130)) + scaleIn(
                        animationSpec = spring(dampingRatio = .86f, stiffness = 420f),
                        initialScale = .96f,
                    ),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WelcomeFeature(Icons.Rounded.ChatBubble, "Сообщения", "Через mesh", SecureMeshColors.Cyan, Modifier.weight(1f))
                        WelcomeFeature(Icons.Rounded.Hub, "Сеть", "Узлы и маршруты", SecureMeshColors.Blue, Modifier.weight(1f))
                        WelcomeFeature(Icons.Rounded.Security, "Защита", "Pairing + identity", SecureMeshColors.Violet, Modifier.weight(1f))
                    }
                }

                AnimatedVisibility(visible = reconnectText != null) {
                    reconnectText?.let {
                        Surface(
                            color = SecureMeshColors.SurfaceHigh.copy(alpha = .88f),
                            shape = MaterialTheme.shapes.extraLarge,
                            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .18f)),
                        ) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Автоподключение", fontWeight = FontWeight.Bold)
                                Text(it, color = SecureMeshColors.TextSecondary)
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = SecureMeshColors.Cyan)
                                TextButton(onClick = onCancelReconnect, modifier = Modifier.align(Alignment.End)) { Text("Отменить") }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(450, delayMillis = 190)) + slideInVertically(tween(470, delayMillis = 190)) { it / 7 },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VibrantPrimaryButton(
                            text = "Подключить устройство",
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            icon = Icons.Rounded.BluetoothSearching,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(Modifier.size(7.dp), shape = CircleShape, color = SecureMeshColors.Healthy) {}
                            Text(
                                "Все рабочие данные остаются локально на телефоне.",
                                color = SecureMeshColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BrandPulse() {
    val transition = rememberInfiniteTransition(label = "brand-mesh")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "brand-mesh-phase",
    )
    val breathe = transition.animateFloat(
        initialValue = .90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(tween(1500), repeatMode = RepeatMode.Reverse),
        label = "brand-mesh-breathe",
    )
    val float = transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), repeatMode = RepeatMode.Reverse),
        label = "brand-mesh-float",
    )

    Box(
        Modifier
            .size(104.dp)
            .graphicsLayer { translationY = float.value * 2.6f },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .5f, size.height * .5f)
            val points = listOf(
                Offset(size.width * .18f, size.height * .28f),
                Offset(size.width * .82f, size.height * .22f),
                Offset(size.width * .85f, size.height * .76f),
                Offset(size.width * .20f, size.height * .80f),
            )

            drawCircle(
                SecureMeshColors.Cyan.copy(alpha = .035f),
                radius = size.minDimension * .46f * breathe.value,
                center = center,
            )
            drawCircle(
                SecureMeshColors.Blue.copy(alpha = .12f),
                radius = size.minDimension * .34f,
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )

            points.forEachIndexed { index, p ->
                drawLine(
                    SecureMeshColors.Cyan.copy(alpha = .30f),
                    center,
                    p,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val t = (phase.value + index * .21f) % 1f
                val pulse = Offset(center.x + (p.x - center.x) * t, center.y + (p.y - center.y) * t)
                drawCircle(SecureMeshColors.Cyan.copy(alpha = .10f), 8.dp.toPx(), pulse)
                drawCircle(SecureMeshColors.CyanHot.copy(alpha = .55f), 3.5.dp.toPx(), pulse)
                drawCircle(SecureMeshColors.Text, 1.2.dp.toPx(), pulse)

                drawCircle(SecureMeshColors.Blue.copy(alpha = .10f), 10.dp.toPx(), p)
                drawCircle(if (index % 2 == 0) SecureMeshColors.Cyan else SecureMeshColors.Violet, 4.2.dp.toPx(), p)
            }

            drawCircle(SecureMeshColors.Cyan.copy(alpha = .14f), 18.dp.toPx() * breathe.value, center)
            drawCircle(SecureMeshColors.GraphiteSoft, 12.dp.toPx(), center)
            drawCircle(SecureMeshColors.CyanHot, 6.5.dp.toPx(), center)
            drawCircle(SecureMeshColors.Text, 2.dp.toPx(), center)
        }

        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Hub,
                    contentDescription = null,
                    tint = SecureMeshColors.CyanHot.copy(alpha = .90f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun WelcomeFeature(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .92f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, accent.copy(alpha = .24f)),
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 9.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = .14f)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(9.dp).size(22.dp))
            }
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, color = SecureMeshColors.Text)
            Text(subtitle, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
