@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.securemesh.commander.feature.nodes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.securemesh.commander.core.ui.SecureMeshColors
import dev.securemesh.commander.domain.model.ContactProfile

@Composable
fun ContactEditorDialog(
    nodeId: String,
    initial: ContactProfile?,
    onSave: (alias: String?, note: String?, pinned: Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var alias by remember(initial?.updatedAtEpochMs, nodeId) { mutableStateOf(initial?.alias.orEmpty()) }
    var note by remember(initial?.updatedAtEpochMs, nodeId) { mutableStateOf(initial?.note.orEmpty()) }
    var pinned by remember(initial?.updatedAtEpochMs, nodeId) { mutableStateOf(initial?.notePinned == true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = SecureMeshColors.CyanHot) },
        title = { Text("Локальный контакт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Node ID $nodeId остаётся неизменной trust-identity. Имя и заметка существуют только на этом телефоне и сохраняются в зашифрованном хранилище.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecureMeshColors.TextSecondary,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { if (it.length <= 48) alias = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Имя контакта") },
                    placeholder = { Text("Например: Группа 2") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 2_000) note = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    label = { Text("Заметка") },
                    placeholder = { Text("Локальная информация о контакте") },
                    minLines = 4,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PushPin, contentDescription = null, tint = if (pinned) SecureMeshColors.CyanHot else SecureMeshColors.Muted)
                    Spacer(Modifier.width(8.dp))
                    Text("Закрепить заметку", modifier = Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = { pinned = it }, enabled = note.isNotBlank())
                }
                Text(
                    "AES-256-GCM · ключ Android Keystore · данные не меняют радио-протокол и не отправляются в mesh.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SecureMeshColors.Muted,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(alias.trim().takeIf(String::isNotEmpty), note.trim().takeIf(String::isNotEmpty), pinned && note.isNotBlank())
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onClear(); onDismiss() }) { Text("Очистить") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
