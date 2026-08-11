package dev.securemesh.commander.feature.more
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.SecureMeshSession
import dev.securemesh.commander.domain.service.UiAccessPolicy
@Composable fun MoreScreen(session:SecureMeshSession?,open:(String)->Unit){val items=buildList{if(UiAccessPolicy.canShowTopology(session))add(Triple("Network","Directional topology","topology"));if(UiAccessPolicy.canShowRoutes(session))add(Triple("Routes","DIRECT / STATIC route state","routes"));if(UiAccessPolicy.canRunFieldTest(session))add(Triple("Field Tests","Local-node radio/routing tests","fieldtest"));if(UiAccessPolicy.canShowSystemLog(session))add(Triple("Events","Authorized event log","events"));if(UiAccessPolicy.canShowDiagnostics(session))add(Triple("Diagnostics","Session and network diagnostics","diagnostics"));add(Triple("Global Search","Search only visible data","search"));add(Triple("Settings","Bluetooth, storage and developer settings","settings"))};LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){item{Text("MORE",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);Text("Tools are projected from capabilities + session permissions.",color=SecureMeshColors.Muted)};items.forEach{(t,d,r)->item(r){TechnicalCard(t){Text(d,color=SecureMeshColors.Muted);TextButton({open(r)}){Text("OPEN →")}}}}}}
