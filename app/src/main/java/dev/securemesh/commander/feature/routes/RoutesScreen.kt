package dev.securemesh.commander.feature.routes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable fun RoutesScreen(vm:RoutesViewModel){val s by vm.uiState.collectAsStateWithLifecycle();if(!s.canView)return EmptyState("Routes unavailable","VIEW_ROUTES was not granted.");var d by remember{mutableStateOf("")};var v by remember{mutableStateOf("")};LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("ROUTES",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);Text("Unknown hop count/quality stay UNKNOWN unless firmware reports them.",color=SecureMeshColors.Muted)};if(s.canManage)item{TechnicalCard("Add static route"){OutlinedTextField(d,{d=it},label={Text("Destination node ID")});OutlinedTextField(v,{v=it},label={Text("Via node ID")});Button({vm.add(d,v)},enabled=d.isNotBlank()&&v.isNotBlank()){Text("SAVE")}}};if(s.routes.isEmpty())item{EmptyState("No routes","No authorized route data.")}else items(s.routes,key={it.destination}){r->TechnicalCard("Destination ${r.destination}"){Metric("Next hop",r.nextHop);Metric("Type",r.type.name);Metric("Hops",r.hopCount?.toString()?:"UNKNOWN");Metric("Quality",r.quality?.let{percent(it)}?:"UNKNOWN");Text("Age ${r.updatedAtEpochMs?.let(::ageLabel)?:"UNKNOWN"}",color=SecureMeshColors.Muted);if(s.canManage&&r.type==RouteType.STATIC)TextButton({vm.remove(r.destination)}){Text("REMOVE ROUTE")}}}}}
