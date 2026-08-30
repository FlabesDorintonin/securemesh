@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.securemesh.commander.feature.messages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.*
import dev.securemesh.commander.domain.model.*

@Composable
fun MessagesScreen(viewModel: MessagesViewModel, initialPeerId: String? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()

    if (!state.canView) {
        EmptyState("Чаты недоступны", "У текущей сессии нет права на просмотр сообщений.")
        return
    }

    val remotes = state.nodes.filter { it.id != state.localNodeId }
    var selectedPeerId by rememberSaveable(initialPeerId) { mutableStateOf(initialPeerId) }
    var showNewChat by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPeerId) { if (initialPeerId != null) selectedPeerId = initialPeerId }
    var selectedMessage by remember { mutableStateOf<MeshMessage?>(null) }

    AnimatedContent(
        targetState = selectedPeerId,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(spring(dampingRatio = .88f, stiffness = 500f)) { it / 4 } + fadeIn(tween(190))) togetherWith
                    (slideOutHorizontally(tween(170)) { -it / 7 } + fadeOut(tween(120)))
            } else {
                (slideInHorizontally(spring(dampingRatio = .88f, stiffness = 500f)) { -it / 4 } + fadeIn(tween(190))) togetherWith
                    (slideOutHorizontally(tween(170)) { it / 7 } + fadeOut(tween(120)))
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
                contacts = state.contactProfiles,
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
                sending = sending,
                error = localizedError(error),
                onBack = { selectedPeerId = null },
                onSend = { text, onAccepted -> viewModel.send(peerId, text, onAccepted) },
                onMessageDetails = { selectedMessage = it },
                contact = state.contactProfiles[peerId],
            )
        }
    }

    if (showNewChat) {
        ModalBottomSheet(
            onDismissRequest = { showNewChat = false },
            containerColor = SecureMeshColors.SurfaceHigh,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Новый чат", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Выбери доступный узел SecureMesh", color = SecureMeshColors.Muted)
                Spacer(Modifier.height(5.dp))
                if (remotes.isEmpty()) {
                    EmptyState("Нет доступных узлов", "Сессия пока не показывает узлы для переписки.")
                } else {
                    remotes.forEach { node ->
                        val fallbackName = deviceDisplayName(node.name)
                        val name = state.contactProfiles[node.id]?.displayName(fallbackName) ?: fallbackName
                        PressScaleSurface(
                            onClick = {
                                selectedPeerId = node.id
                                showNewChat = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent,
                            border = null,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MeshAvatar(name, node.online, size = 46.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontWeight = FontWeight.SemiBold)
                                    Text("${node.role.ruLabel()} · ${node.id}", color = SecureMeshColors.Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("›", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
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
    contacts: Map<NodeId, ContactProfile>,
) {
    val conversations = nodes.mapNotNull { node ->
        val last = messages
            .filter { (it.origin == node.id && it.destination == localNodeId) || (it.origin == localNodeId && it.destination == node.id) }
            .maxByOrNull { it.createdAtEpochMs }
        last?.let { node to it }
    }.sortedByDescending { it.second.createdAtEpochMs }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Чаты", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
                        Text("Локальная защищённая связь", color = SecureMeshColors.Muted, style = MaterialTheme.typography.labelSmall)
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
                text = { Text("Новый чат", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StaggeredReveal(entered, 20) {
                    Surface(
                        color = SecureMeshColors.Cyan.copy(alpha = .068f),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = .13f)),
                    ) {
                        Text(
                            "Локальные сообщения через mesh-сеть · hop-ACK не считается финальной доставкой",
                            color = SecureMeshColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    StaggeredReveal(entered, 80) {
                        EmptyState(
                            "Переписок пока нет",
                            "Нажми «Новый чат», выбери узел и отправь первое сообщение.",
                            "Новый чат",
                            onNewChat,
                        )
                    }
                }
            } else {
                itemsIndexed(conversations, key = { _, item -> item.first.id }) { index, (node, message) ->
                    StaggeredReveal(entered, (55 + index * 34).coerceAtMost(210)) {
                        ConversationRow(node, message, localNodeId, contacts[node.id]) { onOpen(node.id) }
                    }
                }
            }
            item { Spacer(Modifier.height(92.dp)) }
        }
    }
}

@Composable
private fun ConversationRow(node: MeshNode, message: MeshMessage, localNodeId: NodeId?, contact: ContactProfile?, onClick: () -> Unit) {
    val fallbackName = deviceDisplayName(node.name)
    val name = contact?.displayName(fallbackName) ?: fallbackName
    PressScaleSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = SecureMeshColors.SurfaceHigh.copy(alpha = .82f),
        border = BorderStroke(1.dp, if (node.online) SecureMeshColors.Cyan.copy(alpha = .14f) else SecureMeshColors.Divider.copy(alpha = .62f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeshAvatar(name, node.online, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.origin == localNodeId) {
                        Text("Вы: ", color = SecureMeshColors.CyanHot, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        message.payload,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = SecureMeshColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (message.origin == localNodeId) {
                        Spacer(Modifier.width(7.dp))
                        DeliveryGlyph(message.finalState)
                    }
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
    sending: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onMessageDetails: (MeshMessage) -> Unit,
    contact: ContactProfile?,
) {
    var text by remember(peerId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val fallbackName = deviceDisplayName(peer?.name ?: peerId)
    val peerName = contact?.displayName(fallbackName) ?: fallbackName

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MeshAvatar(peerName, peer?.online, size = 40.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Surface(Modifier.size(6.dp), shape = CircleShape, color = if (peer?.online == true) SecureMeshColors.Healthy else SecureMeshColors.Muted) {}
                                Text(
                                    if (peer?.online == true) "в сети" else "не в сети",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (peer?.online == true) SecureMeshColors.Healthy else SecureMeshColors.Muted,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SecureMeshColors.Graphite.copy(alpha = .92f)),
            )
        },
        bottomBar = {
            MessageComposer(
                text = text,
                onText = { text = fitMessageDraftToProtocol(it) },
                enabled = canSend && peer?.online != false && !sending,
                sending = sending,
                error = error,
                onSend = {
                    val value = text.trim()
                    if (value.isNotEmpty()) onSend(value) { text = "" }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ChatPattern(Modifier.fillMaxSize())
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState("Начни диалог", "Сообщения этой переписки появятся здесь.")
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.stableKey() }) { message ->
                        MessageBubble(message, outgoing = message.origin == localNodeId) { onMessageDetails(message) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPattern(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val spacing = 42.dp.toPx()
        var y = spacing / 2f
        while (y < size.height) {
            var x = spacing / 2f
            while (x < size.width) {
                drawCircle(SecureMeshColors.Cyan.copy(alpha = .022f), radius = 1.2f, center = androidx.compose.ui.geometry.Offset(x, y))
                x += spacing
            }
            y += spacing
        }
    }
}

@Composable
private fun MessageBubble(message: MeshMessage, outgoing: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        PressScaleSurface(
            onClick = onClick,
            modifier = Modifier.widthIn(max = 330.dp),
            color = if (outgoing) SecureMeshColors.BubbleOutgoing else SecureMeshColors.BubbleIncoming,
            shape = if (outgoing) RoundedCornerShape(21.dp, 21.dp, 7.dp, 21.dp) else RoundedCornerShape(21.dp, 21.dp, 21.dp, 7.dp),
            border = BorderStroke(
                1.dp,
                if (outgoing) SecureMeshColors.Cyan.copy(alpha = .18f) else SecureMeshColors.Divider.copy(alpha = .62f),
            ),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(message.payload, style = MaterialTheme.typography.bodyLarge, color = SecureMeshColors.Text)
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(clockLabel(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = SecureMeshColors.Muted)
                    if (outgoing) DeliveryGlyph(message.finalState)
                }
            }
        }
    }
}

@Composable
private fun DeliveryGlyph(state: MessageFinalState) {
    val targetColor = when (state) {
        MessageFinalState.DELIVERED -> SecureMeshColors.Healthy
        MessageFinalState.FAILED, MessageFinalState.EXPIRED -> SecureMeshColors.Critical
        MessageFinalState.UNKNOWN -> SecureMeshColors.Warning
        MessageFinalState.PENDING -> SecureMeshColors.Muted
    }
    val color by animateColorAsState(targetColor, tween(180), label = "delivery-color")
    val transition = rememberInfiniteTransition(label = "delivery-pending")
    val pendingAlpha = transition.animateFloat(
        initialValue = .40f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(780), repeatMode = RepeatMode.Reverse),
        label = "delivery-pending-alpha",
    )
    val alpha = if (state == MessageFinalState.PENDING) pendingAlpha.value else 1f
    val symbol = when (state) {
        MessageFinalState.DELIVERED -> "✓✓"
        MessageFinalState.FAILED, MessageFinalState.EXPIRED -> "!"
        MessageFinalState.UNKNOWN -> "?"
        MessageFinalState.PENDING -> "→"
    }
    Text(
        symbol,
        color = color.copy(alpha = alpha),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.ExtraBold,
    )
}

@Composable
private fun MessageComposer(
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    sending: Boolean,
    error: String?,
    onSend: () -> Unit,
) {
    val utf8Bytes = messageUtf8Bytes(text)
    val sendScale by animateFloatAsState(
        targetValue = if (enabled && text.isNotBlank()) 1f else .90f,
        animationSpec = spring(dampingRatio = .78f, stiffness = 620f),
        label = "send-button-scale",
    )

    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!enabled) {
            Text(
                if (sending) "Отправляем…" else "Отправка сейчас недоступна",
                modifier = Modifier.padding(horizontal = 12.dp),
                color = if (sending) SecureMeshColors.Cyan else SecureMeshColors.Warning,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        error?.let {
            Text(it, modifier = Modifier.padding(horizontal = 12.dp), color = SecureMeshColors.Critical, style = MaterialTheme.typography.labelSmall)
        }
        Surface(
            color = SecureMeshColors.Navigation.copy(alpha = .96f),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, SecureMeshColors.Cyan.copy(alpha = if (text.isNotBlank()) .22f else .10f)),
            shadowElevation = 10.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(50.dp).graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SecureMeshColors.Cyan,
                        contentColor = Color(0xFF001E28),
                        disabledContainerColor = SecureMeshColors.SurfaceBright,
                        disabledContentColor = SecureMeshColors.Muted,
                    ),
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Отправить")
                }
            }
        }
        Text(
            "$utf8Bytes/$SECUREMESH_MESSAGE_MAX_UTF8_BYTES байт UTF-8",
            modifier = Modifier.padding(horizontal = 12.dp),
            color = if (utf8Bytes >= 60) SecureMeshColors.Warning else SecureMeshColors.Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MessageDetailsSheet(message: MeshMessage, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SecureMeshColors.SurfaceHigh) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = SecureMeshColors.Cyan.copy(alpha = .12f)) {
                    Icon(Icons.Rounded.Chat, contentDescription = null, tint = SecureMeshColors.CyanHot, modifier = Modifier.padding(10.dp).size(22.dp))
                }
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
