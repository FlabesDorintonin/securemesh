package dev.securemesh.commander.feature.welcome
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable fun WelcomeScreen(viewModel:WelcomeViewModel,onConnect:()->Unit,onDemo:()->Unit,onAutoConnected:(Boolean)->Unit={}){val connection by viewModel.connection.collectAsStateWithLifecycle();val session by viewModel.session.collectAsStateWithLifecycle();val mode by viewModel.transportMode.collectAsStateWithLifecycle();LaunchedEffect(connection,session,mode){if(mode==TransportMode.BLE&&connection is MeshConnectionState.Connected)onAutoConnected(session?.authenticationState==AuthenticationState.AUTHENTICATED)};WelcomeContent({viewModel.prepareBle(onConnect)},{viewModel.launchDemo(DemoProfile.CURRENT_FIRMWARE_V05,onDemo)},{viewModel.launchDemo(DemoProfile.FUTURE_DEMO,onDemo)},(connection as? MeshConnectionState.Reconnecting)?.let{"Reconnecting trusted node ${it.identityHint} · attempt ${it.attempt}/3"},viewModel::cancelReconnect)}
@Composable fun WelcomeContent(onConnect:()->Unit,onCurrentDemo:()->Unit,onFutureDemo:()->Unit,reconnectText:String?=null,onCancelReconnect:()->Unit={}){Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){Column(Modifier.widthIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text("SECUREMESH",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Black,color=SecureMeshColors.Cyan);Text("One Android app · local BLE node · permission-adaptive UI",color=SecureMeshColors.Muted);TechnicalCard("Connection model"){Text("Phone → BLE local node → SecureMesh network",fontWeight=FontWeight.SemiBold);Text("Role describes purpose. Authenticated session permissions decide visible operations; firmware remains the security authority.",color=SecureMeshColors.Muted)};reconnectText?.let{TechnicalCard("Auto reconnect"){Text(it);OutlinedButton(onClick=onCancelReconnect){Text("CANCEL")}}};Button(onClick=onConnect,modifier=Modifier.fillMaxWidth()){Text("CONNECT DEVICE")};OutlinedButton(onClick=onCurrentDemo,modifier=Modifier.fillMaxWidth()){Text("DEMO · CURRENT FIRMWARE v0.5")};OutlinedButton(onClick=onFutureDemo,modifier=Modifier.fillMaxWidth()){Text("DEMO · FUTURE SECUREMESH")};Text("Current Firmware mode intentionally does not fake GPS, SOS, dynamic routing or E2E delivery ACK.",color=SecureMeshColors.Warning,style=MaterialTheme.typography.bodySmall)}}}
