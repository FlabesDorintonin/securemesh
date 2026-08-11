package dev.securemesh.commander.feature.nodes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
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
fun NodesScreen(viewModel: NodesViewModel, onNode: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Узлы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Люди и ретрансляторы, которые видит текущая защищённая сессия", color = SecureMeshColors.Muted)
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::query,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Имя или ID узла") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showFilters,
                    onClick = { showFilters = !showFilters },
                    leadingIcon = { Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text("Фильтры") },
                )
                NodeSortMenu(state.sort, viewModel::sort)
            }
        }
        item {
            AnimatedVisibility(visible = showFilters) {
                NodeFilterRow(state.filters, viewModel::filters)
            }
        }

        if (state.nodes.isEmpty()) {
            item { EmptyState("Ничего не найдено", "Попробуй изменить поиск или фильтры.") }
        } else {
            items(state.nodes, key = { it.node.id }) { item ->
                NodeContactRow(item) { onNode(item.node.id) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun NodeFilterRow(filters: NodeFilters, set: (NodeFilters) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item { FilterChip(filters.onlineOnly, { set(filters.copy(onlineOnly = !filters.onlineOnly, offlineOnly = false)) }, { Text("В сети") }) }
        item { FilterChip(filters.offlineOnly, { set(filters.copy(offlineOnly = !filters.offlineOnly, onlineOnly = false)) }, { Text("Не в сети") }) }
        item { FilterChip(filters.relay, { set(filters.copy(relay = !filters.relay)) }, { Text("Ретрансляторы") }) }
        item { FilterChip(filters.commander, { set(filters.copy(commander = !filters.commander)) }, { Text("Командиры") }) }
        item { FilterChip(filters.gpsLost, { set(filters.copy(gpsLost = !filters.gpsLost)) }, { Text("Нет GPS") }) }
        item { FilterChip(filters.weakLink, { set(filters.copy(weakLink = !filters.weakLink)) }, { Text("Слабая связь") }) }
    }
}

@Composable
private fun NodeSortMenu(sort: NodeSort, set: (NodeSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            leadingIcon = { Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(sortLabel(sort)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NodeSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    onClick = { set(option); open = false },
                )
            }
        }
    }
}

private fun sortLabel(sort: NodeSort): String = when (sort) {
    NodeSort.NAME -> "По имени"
    NodeSort.RSSI -> "По сигналу"
    NodeSort.BATTERY -> "По заряду"
    NodeSort.LAST_SEEN -> "По активности"
}

@Composable
private fun NodeContactRow(item: NodeListItem, onOpen: () -> Unit) {
    val node = item.node
    val name = deviceDisplayName(node.name)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = SecureMeshColors.Surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeshAvatar(name, node.online, size = 50.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(if (node.online) "в сети" else ageLabel(node.lastSeenEpochMs), style = MaterialTheme.typography.labelSmall, color = if (node.online) SecureMeshColors.Healthy else SecureMeshColors.Muted)
                }
                Text("${node.role.ruLabel()} · ${node.id}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("RSSI ${dbm(item.primaryLink?.rssi)}", color = linkQualityColor(item.primaryLink?.quality() ?: LinkQuality.UNKNOWN), style = MaterialTheme.typography.labelSmall)
                    Text("SNR ${snr(item.primaryLink?.snr)}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                    node.batteryPercent?.let { Text("$it%", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun NodeDetailsScreen(viewModel: NodeDetailsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val node = state.node ?: return EmptyState("Узел недоступен", "Текущая сессия не разрешает просмотр этого узла.", "Назад", onBack)
    val name = deviceDisplayName(node.name)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MeshAvatar(name, node.online, size = 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${node.role.ruLabel()} · ${node.id}", color = SecureMeshColors.Muted)
                    Spacer(Modifier.height(5.dp))
                    StatusChip(if (node.online) "В сети" else "Не в сети", if (node.online) SecureMeshColors.Healthy else SecureMeshColors.Muted)
                }
            }
        }

        item {
            TechnicalCard("Устройство") {
                DetailRows(
                    listOf(
                        "ID" to node.id,
                        "Роль" to node.role.ruLabel(),
                        "Прошивка" to (node.firmwareVersion ?: "Нет данных"),
                        "Протокол" to (node.protocolVersion?.toString() ?: "Нет данных"),
                        "Последняя активность" to ageLabel(node.lastSeenEpochMs),
                        "Время работы" to (node.uptimeSec?.let { "${it / 60} мин" } ?: "Нет данных"),
                    ),
                )
            }
        }

        item {
            TechnicalCard("Связь") {
                if (state.links.isEmpty()) {
                    Text("Нет данных о направленных связях", color = SecureMeshColors.Muted)
                } else {
                    state.links.forEachIndexed { index, link ->
                        if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider)
                        Text("${link.fromNode} → ${link.toNode}", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("RSSI", dbm(link.rssi), Modifier.weight(1f), linkQualityColor(link.quality()))
                            Metric("SNR", snr(link.snr), Modifier.weight(1f))
                            Metric("PDR", percent(link.pdr), Modifier.weight(1f))
                        }
                        Text("Повторы: ${link.retries ?: "—"} · данные ${link.lastSeenEpochMs?.let(::ageLabel) ?: "неизвестно"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            TechnicalCard("Маршруты") {
                if (state.routes.isEmpty()) {
                    Text("Маршруты не предоставлены", color = SecureMeshColors.Muted)
                } else {
                    state.routes.forEachIndexed { index, route ->
                        if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider)
                        Text("${route.destination} через ${route.nextHop}", fontWeight = FontWeight.SemiBold)
                        Text("${route.type.ruLabel()} · переходов ${route.hopCount ?: "—"} · качество ${route.quality?.let(::percent) ?: "—"}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Заряд", node.batteryPercent?.let { "$it%" } ?: "—", Modifier.weight(1f), SecureMeshColors.Cyan)
                MetricTile("Напряжение", voltage(node.voltage), Modifier.weight(1f), SecureMeshColors.Blue)
            }
        }

        item {
            TechnicalCard("GPS") {
                val position = node.position
                if (position == null) {
                    Text("Координаты не предоставлены", color = SecureMeshColors.Muted)
                } else {
                    DetailRows(
                        listOf(
                            "Статус" to position.status(System.currentTimeMillis()).ruLabel(),
                            "Широта" to coordinate(position.latitude),
                            "Долгота" to coordinate(position.longitude),
                            "Спутники" to (position.satellites?.toString() ?: "—"),
                            "HDOP" to (position.hdop?.toString() ?: "—"),
                            "Возраст" to ageLabel(position.timestampEpochMs),
                        ),
                    )
                }
            }
        }

        item {
            TechnicalCard("Возможности") {
                if (node.capabilities.isEmpty()) Text("Не объявлены", color = SecureMeshColors.Muted)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    node.capabilities.forEach { capability -> AssistChip(onClick = {}, label = { Text(capability.ruLabel()) }) }
                }
            }
        }

        item {
            TechnicalCard("Последняя активность") {
                if (state.events.isEmpty()) Text("Событий не видно", color = SecureMeshColors.Muted)
                else state.events.take(6).forEach { event ->
                    Text("${clockLabel(event.timestampEpochMs)} · ${localizedTechnicalText(event.title)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun DetailRows(rows: List<Pair<String, String>>) {
    rows.forEachIndexed { index, (label, value) ->
        if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider.copy(alpha = .65f))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = SecureMeshColors.Muted, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.Medium)
        }
    }
}
