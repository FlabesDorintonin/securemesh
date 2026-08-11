package dev.securemesh.commander.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black) }
        item { TechnicalCard("General") { SettingValue("Theme", s.theme); SettingValue("Units", s.units); SettingSwitch("Keep screen awake during test", s.keepScreenAwakeDuringTest) { v -> viewModel.update { it.copy(keepScreenAwakeDuringTest=v) } } } }
        item { TechnicalCard("Bluetooth") { SettingSwitch("Auto reconnect", s.autoReconnect) { v -> viewModel.update { it.copy(autoReconnect=v) } }; SettingStepper("Scan duration", "${s.scanDurationSec}s", onMinus = { viewModel.update { it.copy(scanDurationSec=(it.scanDurationSec-1).coerceAtLeast(5)) } }, onPlus = { viewModel.update { it.copy(scanDurationSec=(it.scanDurationSec+1).coerceAtMost(30)) } }); SettingSwitch("Show unknown BLE", s.showUnknownBle) { v -> viewModel.update { it.copy(showUnknownBle=v) } }; SettingSwitch("Remember trusted SecureMesh identity", s.rememberTrustedNode) { v -> viewModel.update { it.copy(rememberTrustedNode=v) } } } }
        item { TechnicalCard("Map") { SettingValue("Offline map", "provider / tile packs placeholder"); SettingSwitch("Position history", s.positionHistory) { v -> viewModel.update { it.copy(positionHistory=v) } } } }
        item { TechnicalCard("Logging") { SettingSwitch("Store events", s.storeEvents) { v -> viewModel.update { it.copy(storeEvents=v) } }; SettingStepper("Retention", "${s.retentionDays} days", onMinus = { viewModel.update { it.copy(retentionDays=(it.retentionDays-1).coerceAtLeast(1)) } }, onPlus = { viewModel.update { it.copy(retentionDays=(it.retentionDays+1).coerceAtMost(365)) } }); SettingValue("Export logs", "JSON / CSV via local document picker") } }
        item { TechnicalCard("Developer") { SettingSwitch("Development mode", s.developerMode) { v -> viewModel.update { it.copy(developerMode=v) } }; SettingSwitch("Mock mode", s.mockMode) { v -> viewModel.update { it.copy(mockMode=v) } }; SettingSwitch("Raw BLE", s.rawBle) { v -> viewModel.update { it.copy(rawBle=v) } }; SettingSwitch("Verbose logs", s.verboseLogs) { v -> viewModel.update { it.copy(verboseLogs=v) } }; SettingSwitch("Simulate failures", s.simulateFailures) { v -> viewModel.update { it.copy(simulateFailures=v) } } } }
    }
}
@Composable private fun SettingValue(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text(value,color=SecureMeshColors.Muted)}}
@Composable private fun SettingSwitch(label:String,value:Boolean,set:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f));Switch(value,set)}}

@Composable private fun SettingStepper(label:String,value:String,onMinus:()->Unit,onPlus:()->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Row{OutlinedButton(onClick=onMinus,contentPadding=PaddingValues(horizontal=10.dp)){Text("−")};Text(value,Modifier.padding(horizontal=10.dp,vertical=12.dp),color=SecureMeshColors.Muted);OutlinedButton(onClick=onPlus,contentPadding=PaddingValues(horizontal=10.dp)){Text("+")}}}}
