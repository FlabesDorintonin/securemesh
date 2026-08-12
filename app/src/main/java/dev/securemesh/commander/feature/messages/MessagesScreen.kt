@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.securemesh.commander.feature.messages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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

@Composable
fun MessagesScreen(viewModel: MessagesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    if (!state.canView) {
        EmptyState("Сообщения недоступны", "У текущей сессии нет права на просмотр сообщений.")
        return
    }

    val remotes = remember(state.nodes, state.localNodeId) { state.nodes.filter { it.id != state.localNodeId } }
    var selectedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewChat by rememberSaveable { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MeshMessage?>(null) }

    AnimatedContent(
        targetState = selectedPeerId,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(spring(dampingRatio = .94f, stiffness = 610f)) { it / 5 } + fadeIn(tween(145))) togetherWith
                    (slideOutHorizontally(tween(140)) { -it / 8 } + fadeOut(tween(105)))
            } else {
                (slideInHorizontally(spring(dampingRatio = .94f, stiffness = 610f)) { -it / 5 } + fadeIn(tween(145))) togetherWith
                    (slideOutHorizontally(tween(140)) { it / 8 } + fadeOut(tween(105)))
            }
        },
        label = "message-navigation",
    ) { peerId ->
        if (peerId == null) {
            ConversationsScreen(remotes, state.messages, state.localNodeId, { selectedPeerId = it }, { showNewChat = true })
        } else {
            val peer = remotes.firstOrNull { it.id == peerId }
            val conversation = remember(state.messages, peerId, state.localNodeId) {
                state.messages.asSequence().filter {
                    (it.origin == peerId && it.destination == state.localNodeId) ||
                        (it.origin == state.localNodeId && it.destination == peerId)
                }.sortedBy { it.createdAtEpochMs }.toList()
            }
            ChatScreen(
                peer = peer,
                peerId = peerId,
                localNodeId = state.localNodeId,
                messages = conversation,
                canSend = state.canSend,
                error = localizedError(error),
                onBack = { selectedPeerId = null },
                onSend = { text -> viewModel.send(peerId, text) },
                onMessageDetails = { selectedMessage = it },
            )
        }
    }

    if (showNewChat) {
        ModalBottomSheet(onDismissRequest = { showNewChat = false }, containerColor = SecureMeshColors.SurfaceHigh) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OsScreenHeader("Новый чат", "Выбери узел, которому нужно отправить сообщение")
                if (remotes.isEmpty()) EmptyState("Нет доступных узлов", "Сессия пока не показывает ни одного удалённого узла.")
                else remotes.forEach { node ->
                    PeerPickerRow(node) { selectedPeerId = node.id; showNewChat = false }
                }
            }
        }
    }

    selectedMessage?.let { message -> MessageDetailsSheet(message) { selectedMessage = null } }
}

@Composable
private fun ConversationsScreen(
    nodes: List<MeshNode>,
    messages: List<MeshMessage>,
    localNodeId: NodeId?,
    onOpen: (NodeId) -> Unit,
    onNewChat: () -> Unit,
) {
    val conversations = remember(nodes, messages, localNodeId) {
        nodes.mapNotNull { node ->
            val last = messages.asSequence().filter {
                (it.origin == node.id && it.destination == localNodeId) || (it.origin == localNodeId && it.destination == node.id)
            }.maxByOrNull { it.createdAtEpochMs }
            last?.let { node to it }
        }.sortedByDescending { it.second.createdAtEpochMs }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Сообщения", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
                        Text("Локальные чаты через SecureMesh", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewChat,
                containerColor = SecureMeshColors.Cyan,
                contentColor = Color(0xFF001E28),
                shape = MaterialTheme.shapes.extraLarge,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Написать", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                Surface(color = SecureMeshColors.SurfaceHigh, shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .75f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        StatusChip("MESH", SecureMeshColors.Cyan)
                        Text("Hop-ACK подтверждает только ближайший radio hop — финальный статус показывается отдельно.", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (conversations.isEmpty()) {
                item { EmptyState("Переписок пока нет", "Нажми «Написать», выбери узел и отправь первое сообщение.", "Написать", onNewChat) }
            } else {
                items(conversations, key = { it.first.id }) { (node, message) -> ConversationRow(node, message, localNodeId) { onOpen(node.id) } }
            }
            item { Spacer(Modifier.height(92.dp)) }
        }
    }
}

@Composable
private fun PeerPickerRow(node: MeshNode, onOpen: () -> Unit) {
    val name = deviceDisplayName(node.name)
    PressScaleSurface(onClick = onOpen, modifier = Modifier.fillMaxWidth(), color = SecureMeshColors.Surface, border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .7f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeshAvatar(name, node.online, size = 46.dp)
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                Text("${node.role.ruLabel()} · ${node.id}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ConversationRow(node: MeshNode, message: MeshMessage, localNodeId: NodeId?, onClick: () -> Unit) {
    val name = deviceDisplayName(node.name)
    PressScaleSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh,
        border = BorderStroke(1.dp, if (node.online) SecureMeshColors.Cyan.copy(alpha = .16f) else SecureMeshColors.Divider.copy(alpha = .68f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeshAvatar(name, node.online, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.origin == localNodeId) Text("Вы: ", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(message.payload, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

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
    val peerName = deviceDisplayName(peer?.name ?: peerId)
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MeshAvatar(peerName, peer?.online, size = 40.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (peer?.online == true) "в сети · ${peer.id}" else "не в сети · ${peer?.id ?: peerId}", style = MaterialTheme.typography.labelSmall, color = if (peer?.online == true) SecureMeshColors.Healthy else SecureMeshColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SecureMeshColors.Graphite.copy(alpha = .96f)),
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
                    if (value.isNotEmpty()) { onSend(value); text = "" }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { EmptyState("Начни диалог", "Напиши сообщение — оно уйдёт через обычный SecureMesh routing path.") }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageBubble(message, outgoing = message.origin == localNodeId) { onMessageDetails(message) } }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MeshMessage, outgoing: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 330.dp).clickable(onClick = onClick),
            color = if (outgoing) SecureMeshColors.BubbleOutgoing else SecureMeshColors.BubbleIncoming,
            shape = if (outgoing) RoundedCornerShape(21.dp, 21.dp, 7.dp, 21.dp) else RoundedCornerShape(21.dp, 21.dp, 21.dp, 7.dp),
            border = BorderStroke(1.dp, if (outgoing) SecureMeshColors.Cyan.copy(alpha = .18f) else SecureMeshColors.Divider.copy(alpha = .62f)),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(message.payload, style = MaterialTheme.typography.bodyLarge, color = SecureMeshColors.Text)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
                    if (outgoing) Text(
                        message.finalState.ruLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
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

@Composable
private fun MessageComposer(text: String, onText: (String) -> Unit, enabled: Boolean, error: String?, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!enabled) Text("Отправка сейчас недоступна", modifier = Modifier.padding(horizontal = 12.dp), color = SecureMeshColors.Warning, style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp), color = SecureMeshColors.Critical, style = MaterialTheme.typography.labelSmall) }
        Surface(color = SecureMeshColors.Navigation.copy(alpha = .98f), shape = MaterialTheme.shapes.extraLarge, border = BorderStroke(1.dp, SecureMeshColors.Divider.copy(alpha = .85f)), shadowElevation = 12.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(50.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = SecureMeshColors.Cyan, contentColor = Color(0xFF001E28), disabledContainerColor = SecureMeshColors.SurfaceBright, disabledContentColor = SecureMeshColors.Muted),
                ) { Icon(Icons.Rounded.Send, contentDescription = "Отправить") }
            }
        }
    }
}

@Composable
private fun MessageDetailsSheet(message: MeshMessage, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SecureMeshColors.SurfaceHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = SecureMeshColors.Cyan.copy(alpha = .12f)) { Icon(Icons.Rounded.Chat, contentDescription = null, tint = SecureMeshColors.CyanHot, modifier = Modifier.padding(10.dp).size(22.dp)) }
                Text("Детали сообщения", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            }
            TechnicalCard("Состояние") {
                Metric("Откуда", message.origin)
                Metric("Куда", message.destination)
                Metric("Передача", message.progressState.ruLabel())
                Metric("Финальный статус", message.finalState.ruLabel())
                Metric("Время доставки", message.deliveryTimeMs()?.let { "$it мс" } ?: "Нет данных")
            }
            TechnicalCard("Путь по сети") {
                if (message.hopTrace.isEmpty()) Text("Hop-телеметрия недоступна", color = SecureMeshColors.Muted)
                else message.hopTrace.forEachIndexed { index, hop ->
                    if (index > 0) HorizontalDivider(color = SecureMeshColors.Divider)
                    Text("${hop.from} → ${hop.to}", fontWeight = FontWeight.SemiBold)
                    Text("${hop.ackState.ruLabel()} · повторы ${hop.retries ?: "—"} · RSSI ${dbm(hop.rssi)} · SNR ${snr(hop.snr)}", color = SecureMeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
