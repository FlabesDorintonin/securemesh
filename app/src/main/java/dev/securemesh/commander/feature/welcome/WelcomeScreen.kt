package dev.securemesh.commander.feature.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var bootFinished by remember { mutableStateOf(false) }

    LaunchedEffect(connection, session, mode) {
        if (mode == TransportMode.BLE && connection is MeshConnectionState.Connected) {
            onAutoConnected(session?.authenticationState == AuthenticationState.AUTHENTICATED)
        }
    }

    if (!bootFinished) {
        SecureMeshBootSequence(onFinished = { bootFinished = true })
        return
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
    var developerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    MeshBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AnimatedVisibility(visible = entered, enter = fadeIn(tween(280)) + slideInVertically(tween(330)) { -it / 8 }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SECUREMESH", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = SecureMeshColors.CyanHot)
                            Text("Пульт автономной сети", style = MaterialTheme.typography.bodySmall, color = SecureMeshColors.Muted)
                        }
                        StatusChip("ГОТОВ", SecureMeshColors.Healthy)
                    }
                }

                AnimatedVisibility(visible = entered, enter = fadeIn(tween(330, delayMillis = 60)) + slideInVertically(tween(350, delayMillis = 60)) { it / 9 }) {
                    OsHeroCard(
                        eyebrow = "SecureMesh OS",
                        title = "Подключись — остальное понятно само",
                        subtitle = "Телефон управляет только ближайшим узлом по защищённому BLE. Сообщения и тесты дальше идут через обычный SecureMesh radio stack.",
                        accent = SecureMeshColors.Cyan,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Security, contentDescription = null, tint = SecureMeshColors.Healthy)
                            Text("Pairing-код появится на OLED устройства", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                AnimatedVisibility(visible = entered, enter = fadeIn(tween(330, delayMillis = 120)) + slideInVertically(tween(360, delayMillis = 120)) { it / 9 }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VibrantPrimaryButton(
                            text = "Найти устройство",
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            icon = Icons.Rounded.BluetoothSearching,
                        )
                        Text(
                            "1. Включи узел  ·  2. Выбери его  ·  3. Введи код с OLED",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = SecureMeshColors.Muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                AnimatedVisibility(visible = reconnectText != null) {
                    reconnectText?.let {
                        Surface(color = SecureMeshColors.Warning.copy(alpha = .08f), shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, SecureMeshColors.Warning.copy(alpha = .22f))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Переподключение", color = SecureMeshColors.Warning, fontWeight = FontWeight.Bold)
                                Text(it, color = SecureMeshColors.TextSecondary)
                                TextButton(onClick = onCancelReconnect) { Text("Отменить") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                TextButton(onClick = { developerOpen = !developerOpen }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Режим разработки", color = SecureMeshColors.Muted)
                    Icon(if (developerOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = SecureMeshColors.Muted)
                }

                AnimatedVisibility(visible = developerOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedButton(
                            onClick = onCurrentDemo,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            border = BorderStroke(1.dp, SecureMeshColors.Divider),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SecureMeshColors.TextSecondary),
                        ) { Text("Демо текущих возможностей") }
                        TextButton(onClick = onFutureDemo, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Демо будущих возможностей", color = SecureMeshColors.Muted)
                        }
                        Text("Демо не добавляет в прошивку GPS, SOS, dynamic routing или обычный end-to-end ACK.", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
