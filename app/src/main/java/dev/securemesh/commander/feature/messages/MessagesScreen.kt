package dev.securemesh.commander.feature.messages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(viewModel: MessagesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    if (!state.canView) {
        EmptyState("Чаты недоступны", "У текущей сессии нет права на просмотр сообщений.")
        return
    }

    val remotes = state.nodes.filter { it.id != state.localNodeId }
    var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewChat by rememberSaveable { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MeshMessage?>(null) }

    AnimatedContent(
        targetState = selectedPeerId,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "chat-navigation",
    ) { peerId ->
        if (peerId == null) {
            ConversationsScreen(
                nodes = remotes,
                messages = state.messages,
                localNodeId = state.localNodeId,
                onOpen = { selectedPeerId = it },
                onNewChat = { showNewChat = true },
            )
        } else {
            val peer = remotes.firstOrNull { it.id == peerId }
            ChatScreen(
                peer = peer,
                peerId = peerId,
                localNodeId = state.localNodeId,
                messages = state.messages.filter {
                    (it.origin == peerId && it.destination == state.localNodeId) ||
                        (it.origin == state.localNodeId && it.destination == peerId)
                }.sortedBy { it.createdAtEpochMs },
                canSend = state.canSend,
                error = localizedError(error),
                onBack = { selectedPeerId = null },
                onSend = { text -> viewModel.send(peerId, text) },
                onMessageDetails = { selectedMessage = it },
            )
        }
    }

    if (showNewChat) {
        ModalBottomSheet(onDismissRequest = { showNewChat = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Новый чат", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Выбери доступный узел SecureMesh", color = SecureMeshColors.Muted)
                Spacer(Modifier.height(8.dp))
                if (remotes.isEmpty()) {
                    EmptyState("Нет доступных узлов", "Сессия пока не показывает узлы для переписки.")
                } else {
                    remotes.forEach { node ->
                        ListItem(
                            headlineContent = { Text(node.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${node.role.ruLabel()} · ${node.id}") },
                            leadingContent = { MeshAvatar(node.name, node.online, size = 44.dp) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                selectedPeerId = node.id
                                showNewChat = false
                            },
                        )
                    }
                }
            }
        }
    }

    selectedMessage?.let { message ->
        MessageDetailsSheet(message) { selectedMessage = null }
    }
}

@Composable
private fun ConversationsScreen(
    nodes: List<MeshNode>,
    messages: List<MeshMessage>,
    localNodeId: NodeId?,
    onOpen: (NodeId) -> Unit,
    onNewChat: () -> Unit,
) {
    val conversations = nodes.mapNotNull { node ->
        val last = messages
            .filter { (it.origin == node.id && it.destination == localNodeId) || (it.origin == localNodeId && it.destination == node.id) }
            .maxByOrNull { it.createdAtEpochMs }
        last?.let { node to it }
    }.sortedByDescending { it.second.createdAtEpochMs }

    Scaffold(
        containerColor = SecureMeshColors.Graphite,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Чаты", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = SecureMeshColors.Graphite),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewChat,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Новый чат") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    "Сообщения идут через локальный узел и mesh-сеть. Hop-ACK не считается финальной доставкой.",
                    color = SecureMeshColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            if (conversations.isEmpty()) {
                item {
                    EmptyState(
                        "Переписок пока нет",
                        "Нажми «Новый чат», выбери узел и отправь первое сообщение.",
                        "Новый чат",
                        onNewChat,
                    )
                }
            } else {
                items(conversations, key = { it.first.id }) { (node, message) ->
                    ConversationRow(node, message, localNodeId) { onOpen(node.id) }
                    HorizontalDivider(color = SecureMeshColors.Divider.copy(alpha = .7f), modifier = Modifier.padding(start = 72.dp))
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun ConversationRow(node: MeshNode, message: MeshMessage, localNodeId: NodeId?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MeshAvatar(node.name, node.online, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.origin == localNodeId) {
                    Text("Вы: ", color = SecureMeshColors.Cyan, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    message.payload,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = SecureMeshColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    peer: MeshNode?,
    peerId: NodeId,
    localNodeId: NodeId?,
    messages: List<MeshMessage>,
    canSend: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onMessageDetails: (MeshMessage) -> Unit,
) {
    var text by remember(peerId) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = SecureMeshColors.Graphite,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MeshAvatar(peer?.name ?: peerId, peer?.online, size = 38.dp)
                        Column {
                            Text(peer?.name ?: peerId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (peer?.online == true) "в сети" else "не в сети",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (peer?.online == true) SecureMeshColors.Healthy else SecureMeshColors.Muted,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SecureMeshColors.Graphite),
            )
        },
        bottomBar = {
            MessageComposer(
                text = text,
                onText = { text = it },
                enabled = canSend && peer?.online != false,
                error = error,
                onSend = {
                    val value = text.trim()
                    if (value.isNotEmpty()) {
                        onSend(value)
                        text = ""
                    }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState("Начни диалог", "Сообщения этой переписки появятся здесь.")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, outgoing = message.origin == localNodeId) { onMessageDetails(message) }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MeshMessage, outgoing: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 330.dp).clickable(onClick = onClick),
            color = if (outgoing) SecureMeshColors.BubbleOutgoing else SecureMeshColors.BubbleIncoming,
            shape = if (outgoing) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message.payload, style = MaterialTheme.typography.bodyLarge)
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
                    if (outgoing) {
                        Text(
                            message.finalState.ruLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (message.finalState) {
                                MessageFinalState.DELIVERED -> SecureMeshColors.Healthy
                                MessageFinalState.FAILED, MessageFinalState.EXPIRED -> SecureMeshColors.Critical
                                MessageFinalState.UNKNOWN -> SecureMeshColors.Warning
                                else -> SecureMeshColors.Muted
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageComposer(
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    error: String?,
    onSend: () -> Unit,
) {
    Surface(color = SecureMeshColors.Navigation, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            if (!enabled) {
                Text(
                    "Отправка сейчас недоступна",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = SecureMeshColors.Warning,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            error?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp), color = SecureMeshColors.Critical, style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(50.dp),
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageDetailsSheet(message: MeshMessage, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Chat, contentDescription = null, tint = SecureMeshColors.Cyan)
                Text("Детали сообщения", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            TechnicalCard("Состояние") {
                Metric("Откуда", message.origin)
                Metric("Куда", message.destination)
                Metric("Передача", message.progressState.ruLabel())
                Metric("Финальный статус", message.finalState.ruLabel())
                Metric("Время доставки", message.deliveryTimeMs()?.let { "$it мс" } ?: "Нет данных")
            }
            TechnicalCard("Путь по сети") {
                if (message.hopTrace.isEmpty()) {
                    Text("Hop-телеметрия недоступна", color = SecureMeshColors.Muted)
                } else {
                    message.hopTrace.forEachIndexed { index, hop ->
                        if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider)
                        Text("${hop.from} → ${hop.to}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${hop.ackState.ruLabel()} · повторы ${hop.retries ?: "—"} · RSSI ${dbm(hop.rssi)} · SNR ${snr(hop.snr)}",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
