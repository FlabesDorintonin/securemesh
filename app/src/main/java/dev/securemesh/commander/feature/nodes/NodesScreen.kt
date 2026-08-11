package dev.securemesh.commander.feature.nodes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable fun NodesScreen(viewModel:NodesViewModel,onNode:(String)->Unit){val state by viewModel.uiState.collectAsStateWithLifecycle();var show by remember{mutableStateOf(false)};LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
item{Text("NODES",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);Text("Node state is separate from directional radio-link telemetry.",color=SecureMeshColors.Muted)}
item{OutlinedTextField(state.query,viewModel::query,label={Text("Search node ID or name")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(show,{show=!show},{Text("FILTERS")});SortMenu(state.sort,viewModel::sort)}}
if(show)item{NodeFilterRow(state.filters,viewModel::filters)}
if(state.nodes.isEmpty())item{EmptyState("No matching nodes","Only nodes visible to the authenticated session are shown.")}else items(state.nodes,key={it.node.id}){NodeCard(it){onNode(it.node.id)}}}}
@Composable private fun NodeFilterRow(f:NodeFilters,set:(NodeFilters)->Unit){Column{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(f.onlineOnly,{set(f.copy(onlineOnly=!f.onlineOnly,offlineOnly=false))},{Text("ONLINE")});FilterChip(f.offlineOnly,{set(f.copy(offlineOnly=!f.offlineOnly,onlineOnly=false))},{Text("OFFLINE")});FilterChip(f.relay,{set(f.copy(relay=!f.relay))},{Text("RELAY")})};Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(f.commander,{set(f.copy(commander=!f.commander))},{Text("COMMANDER")});FilterChip(f.gpsLost,{set(f.copy(gpsLost=!f.gpsLost))},{Text("GPS LOST")});FilterChip(f.weakLink,{set(f.copy(weakLink=!f.weakLink))},{Text("WEAK LINK")})}}}
@Composable private fun SortMenu(sort:NodeSort,set:(NodeSort)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton({open=true}){Text("SORT: ${sort.name}")};DropdownMenu(open,{open=false}){NodeSort.entries.forEach{DropdownMenuItem({Text(it.name)},{set(it);open=false})}}}}
@Composable private fun NodeCard(item:NodeListItem,onOpen:()->Unit){val n=item.node;TechnicalCard(n.name,Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("NODE ${n.id}",fontWeight=FontWeight.Bold);Text(n.role.name,color=SecureMeshColors.Muted)};StatusChip(if(n.online)"ONLINE" else "OFFLINE",if(n.online)SecureMeshColors.Healthy else SecureMeshColors.Critical)};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Battery",n.batteryPercent?.let{"$it%"}?:"UNKNOWN");Metric("Best link RSSI",dbm(item.primaryLink?.rssi));Metric("SNR",snr(item.primaryLink?.snr))};Text("Radio values are link observations, not node properties.",color=SecureMeshColors.Muted,style=MaterialTheme.typography.bodySmall);TextButton(onClick=onOpen){Text("OPEN NODE →")}}}
@Composable fun NodeDetailsScreen(viewModel:NodeDetailsViewModel,onBack:()->Unit){val s by viewModel.uiState.collectAsStateWithLifecycle();val n=s.node?:return EmptyState("Node unavailable","No authorized node snapshot is available.","BACK",onBack);LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
item{TextButton(onClick=onBack){Text("← NODES")};Text(n.name,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);StatusChip(if(n.online)"ONLINE" else "OFFLINE",if(n.online)SecureMeshColors.Healthy else SecureMeshColors.Critical)}
item{TechnicalCard("Identity"){DetailRows(listOf("ID" to n.id,"Role" to n.role.name,"Firmware" to (n.firmwareVersion?:"UNKNOWN"),"Protocol" to (n.protocolVersion?.toString()?:"UNKNOWN"),"Last seen" to ageLabel(n.lastSeenEpochMs),"Uptime" to (n.uptimeSec?.let{"${it}s"}?:"UNKNOWN")))}}
item{TechnicalCard("Directional links"){if(s.links.isEmpty())Text("UNAVAILABLE",color=SecureMeshColors.Muted) else s.links.forEach{l->Text("${l.fromNode} → ${l.toNode}",fontWeight=FontWeight.Bold);DetailRows(listOf("RSSI" to dbm(l.rssi),"SNR" to snr(l.snr),"PDR" to percent(l.pdr),"Retries" to (l.retries?.toString()?:"UNKNOWN"),"Age" to (l.lastSeenEpochMs?.let(::ageLabel)?:"UNKNOWN")))}}}
item{TechnicalCard("Routes"){if(s.routes.isEmpty())Text("UNAVAILABLE",color=SecureMeshColors.Muted) else s.routes.forEach{r->Text("${r.destination} via ${r.nextHop} · ${r.type}");Text("Hops ${r.hopCount?:"UNKNOWN"} · Quality ${r.quality?.let{percent(it)}?:"UNKNOWN"}",color=SecureMeshColors.Muted)}}}
item{TechnicalCard("Power"){DetailRows(listOf("Battery" to (n.batteryPercent?.let{"$it%"}?:"UNKNOWN"),"Voltage" to voltage(n.voltage)))}}
item{TechnicalCard("GPS"){val p=n.position;if(p==null)Text("UNAVAILABLE",color=SecureMeshColors.Muted) else DetailRows(listOf("Latitude" to coordinate(p.latitude),"Longitude" to coordinate(p.longitude),"Fix" to p.status(System.currentTimeMillis()).name,"Satellites" to (p.satellites?.toString()?:"UNKNOWN"),"HDOP" to (p.hdop?.toString()?:"UNKNOWN"),"Age" to ageLabel(p.timestampEpochMs)))}}
item{TechnicalCard("Capabilities"){Text(n.capabilities.joinToString(" · "){it.name}.ifBlank{"None announced"})}}
item{TechnicalCard("Activity"){if(s.events.isEmpty())Text("No visible events",color=SecureMeshColors.Muted) else s.events.take(8).forEach{Text("${clockLabel(it.timestampEpochMs)} ${it.title}",style=MaterialTheme.typography.bodySmall)}}}}}
@Composable private fun DetailRows(rows:List<Pair<String,String>>){rows.forEach{(a,b)->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(a,color=SecureMeshColors.Muted);Text(b,fontWeight=FontWeight.SemiBold)}}}
