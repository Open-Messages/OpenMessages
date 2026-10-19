package com.hummcare.messages.ui.trash

import android.provider.Telephony
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.local.TrashedMessage
import com.hummcare.messages.data.trash.TrashManager
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.ConfirmDialog
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.rememberRecipients
import com.hummcare.messages.util.TimeFormat
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val trash = container.trash
    val items by remember { container.db.trashDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<TrashedMessage?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (items.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Restore all") }, onClick = {
                                    menuOpen = false
                                    container.applicationScope.launch { trash.restoreAll() }
                                })
                                DropdownMenuItem(text = { Text("Empty Trash") }, onClick = {
                                    menuOpen = false
                                    confirmEmpty = true
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Delete,
                title = "Trash is empty",
                message = "Deleted text messages are kept here for ${TrashManager.RETENTION_DAYS} days.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item(key = "info") {
                Text(
                    "Messages are permanently deleted ${TrashManager.RETENTION_DAYS} days after they're moved to Trash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.id }) { message ->
                val recipient = rememberRecipients(listOf(message.address)).first()
                val daysLeft = TrashManager.RETENTION_DAYS -
                    TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - message.deletedAt)
                val outgoing = message.type != Telephony.Sms.MESSAGE_TYPE_INBOX
                ListItem(
                    overlineContent = { Text(if (outgoing) "You → ${recipient.displayName}" else recipient.displayName) },
                    headlineContent = { Text(message.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${TimeFormat.dateTime(context, message.date)} · ${daysLeft.coerceAtLeast(0)} days left",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    leadingContent = { Avatar(listOf(recipient)) },
                    modifier = Modifier.clickable { target = message },
                )
            }
        }
    }

    target?.let { message ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("Deleted message") },
            text = { Text(message.body) },
            confirmButton = {
                TextButton(onClick = {
                    container.applicationScope.launch { trash.restore(listOf(message)) }
                    target = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    container.applicationScope.launch { trash.deleteForever(listOf(message.id)) }
                    target = null
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty Trash?",
            message = "All ${items.size} messages will be permanently deleted.",
            confirmLabel = "Empty",
            destructive = true,
            onConfirm = { container.applicationScope.launch { trash.empty() } },
            onDismiss = { confirmEmpty = false },
        )
    }
}
