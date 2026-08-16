package com.uhmk.pos.feature.notices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uhmk.pos.core.db.NoticeEntity
import com.uhmk.pos.core.time.Clock
import com.uhmk.pos.core.ui.components.EmptyState
import com.uhmk.pos.core.ui.components.SelectionDropdown
import com.uhmk.pos.core.db.UserEntity

@Composable
fun NoticesScreen(
    state: NoticesUiState,
    onSend: (String, String, String?, String) -> Unit,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onDelete: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var composing by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        floatingActionButton = {
            if (state.session.isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { composing = true },
                    icon = { Icon(Icons.Default.Campaign, contentDescription = null) },
                    text = { Text("New notice") },
                )
            }
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 88.dp,
            ),
        ) {
            if (state.unread > 0) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${state.unread} unread",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = onMarkAllRead) {
                            Icon(Icons.Default.DoneAll, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Mark all read")
                        }
                    }
                }
            }

            if (state.notices.isEmpty()) {
                item {
                    Spacer(Modifier.height(60.dp))
                    EmptyState(
                        title = "No notices",
                        body = if (state.session.isAdmin) {
                            "Send a message to everyone or choose one specific account."
                        } else {
                            "Messages from the store admin will appear here."
                        },
                    )
                }
            }

            items(state.notices, key = { it.id }) { notice ->
                NoticeCard(
                    notice = notice,
                    canDelete = state.session.isAdmin,
                    onClick = { onMarkRead(notice.id) },
                    onDelete = { onDelete(notice.id) },
                )
            }
        }
    }

    if (composing) {
        ComposeNoticeDialog(
            sending = state.sending,
            onDismiss = { composing = false },
            recipients = state.recipients,
            onSend = { title, body, targetUid, targetName ->
                onSend(title, body, targetUid, targetName)
                composing = false
            },
        )
    }
}

@Composable
private fun NoticeCard(
    notice: NoticeEntity,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notice.isRead) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(14.dp)) {
            if (!notice.isRead) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(top = 6.dp, end = 10.dp)
                        .size(8.dp),
                ) {}
            }
            Column(Modifier.weight(1f)) {
                if (notice.title.isNotBlank()) {
                    Text(
                        notice.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (notice.body.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(notice.body, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${notice.senderName} · ${Clock.stamp(notice.sentAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notice.targetUid.isNotBlank()) {
                    Text(
                        "Only for ${notice.targetName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete message")
                }
            }
        }
    }
}

@Composable
private fun ComposeNoticeDialog(
    sending: Boolean,
    recipients: List<UserEntity>,
    onDismiss: () -> Unit,
    onSend: (String, String, String?, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var targetUid by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send notification") },
        text = {
            Column {
                val options = listOf<String?>(null) + recipients.map { it.uid }
                SelectionDropdown(
                    label = "Send to",
                    selected = targetUid,
                    options = options,
                    optionLabel = { uid ->
                        if (uid == null) "Everyone"
                        else recipients.firstOrNull { it.uid == uid }?.let { user ->
                            "${user.displayName} (${user.email})"
                        } ?: "Account"
                    },
                    onSelected = { targetUid = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = recipients.firstOrNull { it.uid == targetUid }?.displayName
                        ?: "Everyone"
                    onSend(title, body, targetUid, name)
                },
                enabled = !sending && (title.isNotBlank() || body.isNotBlank()),
            ) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
