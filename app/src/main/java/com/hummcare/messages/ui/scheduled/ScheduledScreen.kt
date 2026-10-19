package com.hummcare.messages.ui.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.local.addressList
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.rememberRecipients
import com.hummcare.messages.util.TimeFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val scheduled by remember { container.db.scheduledDao().observeAll() }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scheduled messages") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (scheduled.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Schedule,
                title = "No scheduled messages",
                message = "In a conversation, tap + and choose Schedule message.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(scheduled, key = { it.id }) { message ->
                val recipients = rememberRecipients(message.addressList())
                var menuOpen by remember { mutableStateOf(false) }
                ListItem(
                    overlineContent = { Text(recipients.joinToString { it.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    headlineContent = { Text(message.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "Sends ${TimeFormat.dateTime(context, message.sendAt)}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    leadingContent = { Avatar(recipients) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Send now") }, onClick = {
                                    menuOpen = false
                                    container.applicationScope.launch { container.scheduler.sendNow(message.id) }
                                })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    menuOpen = false
                                    container.applicationScope.launch { container.scheduler.cancel(message.id) }
                                })
                            }
                        }
                    },
                    modifier = Modifier.clickable { onOpenThread(message.threadId) },
                )
            }
        }
    }
}
