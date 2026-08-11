package dev.securemesh.commander.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*

@Composable
fun SearchScreen(viewModel: SearchViewModel, onNode: (String) -> Unit) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text("Поиск", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Ищи только среди данных, доступных текущей сессии", color = SecureMeshColors.Muted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::query,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Узел, ID, сообщение или событие") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
        }

        if (text.isBlank()) {
            item { EmptyState("Что найти?", "Введите имя узла, ID, текст сообщения или событие.") }
        } else if (result.nodes.isEmpty() && result.messages.isEmpty() && result.events.isEmpty()) {
            item { EmptyState("Ничего не найдено", "Попробуй другой запрос.") }
        }

        if (result.nodes.isNotEmpty()) {
            item { SectionHeader("Узлы") }
            items(result.nodes, key = { "node-${it.id}" }) { node ->
                val name = deviceDisplayName(node.name)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onNode(node.id) },
                    color = SecureMeshColors.Surface,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MeshAvatar(name, node.online, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text("${node.role.ruLabel()} · ${node.id}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", color = SecureMeshColors.Muted, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        if (result.messages.isNotEmpty()) {
            item { SectionHeader("Сообщения") }
            items(result.messages, key = { "message-${it.id}" }) { message ->
                Surface(color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(message.payload, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${message.origin} → ${message.destination} · ${message.progressState.ruLabel()}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (result.events.isNotEmpty()) {
            item { SectionHeader("События") }
            items(result.events, key = { "event-${it.id}" }) { event ->
                Surface(color = SecureMeshColors.Surface, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(localizedTechnicalText(event.title), fontWeight = FontWeight.SemiBold)
                        Text(localizedTechnicalText(event.details), maxLines = 2, overflow = TextOverflow.Ellipsis, color = SecureMeshColors.TextSecondary)
                        Text("${event.category.ruLabel()} · ${clockLabel(event.timestampEpochMs)}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
