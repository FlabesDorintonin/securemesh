package dev.securemesh.commander.feature.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.ChatBubble
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
    Box(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("SECUREMESH", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SecureMeshColors.Cyan)
                Text("Связь без интернета", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    "Подключи ESP32 по Bluetooth и используй SecureMesh как обычный мессенджер поверх автономной mesh-сети.",
                    color = SecureMeshColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WelcomeFeature(Icons.Rounded.ChatBubble, "Чаты", "Личные сообщения", Modifier.weight(1f))
                WelcomeFeature(Icons.Rounded.BluetoothSearching, "Локально", "BLE → узел", Modifier.weight(1f))
                WelcomeFeature(Icons.Rounded.Security, "Доступ", "По правам", Modifier.weight(1f))
            }

            AnimatedVisibility(visible = reconnectText != null) {
                reconnectText?.let {
                    TechnicalCard("Переподключение") {
                        Text(it, color = SecureMeshColors.TextSecondary)
                        OutlinedButton(onClick = onCancelReconnect) { Text("Отменить") }
                    }
                }
            }

            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Icon(Icons.Rounded.BluetoothSearching, contentDescription = null)
                Spacer(Modifier.width(9.dp))
                Text("Подключить устройство")
            }

            OutlinedButton(
                onClick = onCurrentDemo,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text("Открыть демо текущей прошивки v0.5")
            }

            TextButton(onClick = onFutureDemo, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Посмотреть будущие возможности")
            }

            Text(
                "В демо v0.5 приложение специально не выдумывает GPS, SOS, динамическую маршрутизацию или сквозное подтверждение доставки, если их ещё нет в прошивке.",
                color = SecureMeshColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WelcomeFeature(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = SecureMeshColors.Cyan, modifier = Modifier.size(22.dp))
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
