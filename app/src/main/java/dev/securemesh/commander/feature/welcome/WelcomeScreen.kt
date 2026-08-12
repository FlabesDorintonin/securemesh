package dev.securemesh.commander.feature.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    onDemo: () -> Unit,
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
        onCurrentDemo = { viewModel.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05, onDemo) },
        onFutureDemo = { viewModel.launchDemo(DemoProfile.FUTURE_DEMO, onDemo) },
        reconnectText = (connection as? MeshConnectionState.Reconnecting)?.let {
            "Ищем доверенный узел ${it.identityHint} · попытка ${it.attempt} из 3"
        },
        onCancelReconnect = viewModel::cancelReconnect,
    )
}

@Composable
fun WelcomeContent(
    onConnect: () -> Unit,
    onCurrentDemo: () -> Unit,
    onFutureDemo: () -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { -it / 7 },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BrandPulse()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "SECUREMESH",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = SecureMeshColors.CyanHot,
                            )
                            StatusChip("АВТОНОМНАЯ СЕТЬ", SecureMeshColors.Healthy)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(470, delayMillis = 70)) + slideInVertically(tween(470, delayMillis = 70)) { it / 8 },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            "Связь без интернета",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = SecureMeshColors.Text,
                        )
                        Text(
                            "Подключи ESP32 по Bluetooth и общайся через автономную ячеистую сеть SecureMesh так же удобно, как в обычном мессенджере.",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(470, delayMillis = 130)) + scaleIn(tween(470, delayMillis = 130), initialScale = .96f),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WelcomeFeature(Icons.Rounded.ChatBubble, "Чаты", "Личные сообщения", SecureMeshColors.Cyan, Modifier.weight(1f))
                        WelcomeFeature(Icons.Rounded.BluetoothSearching, "Локально", "BLE → узел", SecureMeshColors.Blue, Modifier.weight(1f))
                        WelcomeFeature(Icons.Rounded.Security, "Доступ", "По правам", SecureMeshColors.Violet, Modifier.weight(1f))
                    }
                }

                AnimatedVisibility(visible = reconnectText != null) {
                    reconnectText?.let {
                        TechnicalCard("Переподключение") {
                            Text(it, color = SecureMeshColors.TextSecondary)
                            OutlinedButton(onClick = onCancelReconnect) { Text("Отменить") }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(470, delayMillis = 190)) + slideInVertically(tween(470, delayMillis = 190)) { it / 7 },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        VibrantPrimaryButton(
                            text = "Подключить устройство",
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                            icon = Icons.Rounded.BluetoothSearching,
                        )

                        OutlinedButton(
                            onClick = onCurrentDemo,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .30f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SecureMeshColors.Text),
                        ) {
                            Text("Открыть демо текущей прошивки v0.6", fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(onClick = onFutureDemo, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Посмотреть будущие возможности", color = SecureMeshColors.CyanHot, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(tween(500, delayMillis = 260)),
                ) {
                    Surface(
                        color = SecureMeshColors.Surface.copy(alpha = .68f),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .65f)),
                    ) {
                        Text(
                            "В демо v0.6 приложение не выдумывает GPS, SOS, динамическую маршрутизацию или обычное сквозное подтверждение доставки, если их нет в прошивке.",
                            modifier = Modifier.padding(14.dp),
                            color = SecureMeshColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BrandPulse() {
    val transition = rememberInfiniteTransition(label = "brand-pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), repeatMode = RepeatMode.Restart),
        label = "brand-pulse-phase",
    )

    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                color = SecureMeshColors.Cyan.copy(alpha = (1f - phase) * .20f),
                radius = radius * (.42f + phase * .50f),
                style = Stroke(width = 2f),
            )
            drawCircle(
                color = SecureMeshColors.Blue.copy(alpha = .16f),
                radius = radius * .62f,
                style = Stroke(width = 2f),
            )
        }
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = SecureMeshColors.Cyan.copy(alpha = .17f),
            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .38f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.BluetoothSearching,
                    contentDescription = null,
                    tint = SecureMeshColors.CyanHot,
                    modifier = Modifier.size(24.dp),
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
