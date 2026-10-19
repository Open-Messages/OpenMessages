package com.hummcare.messages.ui.thread

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.local.ScheduledMessage
import com.hummcare.messages.data.model.DeliveryStatus
import com.hummcare.messages.data.model.Message
import com.hummcare.messages.data.model.MessageKind
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.Composer
import com.hummcare.messages.ui.components.ConfirmDialog
import com.hummcare.messages.util.Otp
import com.hummcare.messages.util.TimeFormat
import com.hummcare.messages.util.copyToClipboard
import com.hummcare.messages.util.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(threadId: Long, onBack: () -> Unit, onForward: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: ThreadViewModel = viewModel { ThreadViewModel(container, threadId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val scheduledAt by vm.scheduledAt.collectAsStateWithLifecycle()
    val quickResponses by vm.quickResponses.collectAsStateWithLifecycle()
    val trashEnabled = container.settings.settings.collectAsStateWithLifecycle().value.trashEnabled
    val listState = rememberLazyListState()

    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    var scheduledTarget by remember { mutableStateOf<ScheduledMessage?>(null) }
    var confirmDeleteThread by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }

    LifecycleResumeEffect(threadId) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }

    // Follow new messages while the user is at (or near) the bottom.
    val newestKey = state.items.firstOrNull()?.key
    LaunchedEffect(newestKey, state.scheduled.size) {
        if (newestKey != null && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            ThreadTopBar(
                state = state,
                onBack = onBack,
                onTogglePin = vm::togglePin,
                onBlock = { confirmBlock = true },
                onUnblock = vm::unblock,
                onDeleteThread = { confirmDeleteThread = true },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(state.scheduled, key = { "scheduled-${it.id}" }) { scheduled ->
                    ScheduledBubble(scheduled, onClick = { scheduledTarget = scheduled })
                }
                items(state.items, key = { it.key }) { item ->
                    when (item) {
                        is ThreadItem.Day -> DayHeader(item.dayStart)
                        is ThreadItem.Bubble -> MessageBubble(
                            item = item,
                            onLongPress = { actionTarget = item.message },
                            onRetry = { vm.retry(item.message) },
                        )
                    }
                }
                if (state.canLoadMore) {
                    item(key = "load-more") {
                        LaunchedEffect(state.items.size) { vm.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
            }
            if (state.blocked) {
                BlockedBanner(onUnblock = vm::unblock)
            } else {
                Composer(
                    text = text,
                    onTextChange = vm::onTextChange,
                    onSend = vm::send,
                    quickResponses = quickResponses,
                    scheduledAt = scheduledAt,
                    onScheduledAtChange = vm::onScheduledAtChange,
                    sims = state.sims,
                    selectedSubscriptionId = state.selectedSubscriptionId,
                    onSelectSim = vm::selectSim,
                    canSend = state.recipients.isNotEmpty(),
                )
            }
        }
    }

    actionTarget?.let { message ->
        MessageActionsSheet(
            message = message,
            starred = message.key in state.starredKeys,
            onDismiss = { actionTarget = null },
            onCopy = { context.copyToClipboard("Message", message.body) },
            onCopyCode = { code -> context.copyToClipboard("Verification code", code) },
            onToggleStar = { vm.toggleStar(message) },
            onShare = { context.shareText(message.body) },
            onForward = { onForward(message.body) },
            onRetry = { vm.retry(message) },
            onDelete = { deleteTarget = message },
        )
    }
    deleteTarget?.let { message ->
        val toTrash = trashEnabled && message.kind == MessageKind.SMS
        ConfirmDialog(
            title = if (toTrash) "Move message to Trash?" else "Delete message?",
            message = if (toTrash) "You can restore it from Trash for 30 days." else "This can't be undone.",
            confirmLabel = if (toTrash) "Move to Trash" else "Delete",
            destructive = true,
            onConfirm = { vm.delete(message) },
            onDismiss = { deleteTarget = null },
        )
    }
    scheduledTarget?.let { scheduled ->
        AlertDialog(
            onDismissRequest = { scheduledTarget = null },
            title = { Text("Scheduled message") },
            text = { Text("Will be sent ${TimeFormat.dateTime(context, scheduled.sendAt)}.\n\n${scheduled.body}") },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.cancelScheduled(scheduled.id); scheduledTarget = null }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { vm.sendScheduledNow(scheduled.id); scheduledTarget = null }) {
                        Text("Send now")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { scheduledTarget = null }) { Text("Close") } },
        )
    }
    if (confirmDeleteThread) {
        ConfirmDialog(
            title = if (trashEnabled) "Move conversation to Trash?" else "Delete conversation?",
            message = if (trashEnabled) "Text messages stay in Trash for 30 days." else "This can't be undone.",
            confirmLabel = if (trashEnabled) "Move to Trash" else "Delete",
            destructive = true,
            onConfirm = { vm.deleteThread(); onBack() },
            onDismiss = { confirmDeleteThread = false },
        )
    }
    if (confirmBlock) {
        ConfirmDialog(
            title = "Block ${state.title}?",
            message = "You won't get calls or messages from this number.",
            confirmLabel = "Block",
            destructive = true,
            onConfirm = vm::block,
            onDismiss = { confirmBlock = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadTopBar(
    state: ThreadUiState,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onDeleteThread: () -> Unit,
) {
    val context = LocalContext.current
    val single = state.single
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { BackButton(onBack) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.recipients, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    val subtitle = when {
                        state.recipients.size > 1 -> "${state.recipients.size} recipients"
                        single?.isSaved == true -> single.address
                        else -> null
                    }
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        actions = {
            if (single != null) {
                IconButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", single.address, null)))
                }) { Icon(Icons.Filled.Call, "Call") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (single != null) {
                        val contactUri = single.contactUri
                        if (contactUri != null) {
                            DropdownMenuItem(text = { Text("View contact") }, onClick = {
                                menuOpen = false
                                context.startActivity(Intent(Intent.ACTION_VIEW, contactUri))
                            })
                        } else {
                            DropdownMenuItem(text = { Text("Add to contacts") }, onClick = {
                                menuOpen = false
                                context.startActivity(
                                    Intent(ContactsContract.Intents.Insert.ACTION)
                                        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                                        .putExtra(ContactsContract.Intents.Insert.PHONE, single.address)
                                )
                            })
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(if (state.pinned) "Unpin" else "Pin to top") },
                        onClick = { menuOpen = false; onTogglePin() },
                    )
                    if (single != null) {
                        DropdownMenuItem(
                            text = { Text(if (state.blocked) "Unblock number" else "Block number") },
                            onClick = { menuOpen = false; if (state.blocked) onUnblock() else onBlock() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete conversation") },
                        onClick = { menuOpen = false; onDeleteThread() },
                    )
                }
            }
        },
    )
}

@Composable
private fun BlockedBanner(onUnblock: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "You blocked this number.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUnblock) { Text("Unblock") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: Message,
    starred: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onCopyCode: (String) -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit,
    onForward: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val code = remember(message.body) { Otp.find(message.body) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                buildString {
                    append(if (message.incoming) "Received " else "Sent ")
                    append(TimeFormat.dateTime(context, message.date))
                    append(if (message.kind == MessageKind.MMS) " · MMS" else " · SMS")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            fun act(block: () -> Unit): () -> Unit = { block(); onDismiss() }
            if (message.body.isNotEmpty()) SheetAction(Icons.Filled.ContentCopy, "Copy text", act(onCopy))
            if (code != null) SheetAction(Icons.Filled.Password, "Copy code $code", act { onCopyCode(code) })
            SheetAction(if (starred) Icons.Filled.Star else Icons.Filled.StarBorder, if (starred) "Unstar" else "Star", act(onToggleStar))
            if (message.body.isNotEmpty()) {
                SheetAction(Icons.Filled.Share, "Share", act(onShare))
                SheetAction(Icons.AutoMirrored.Filled.Send, "Forward", act(onForward))
            }
            if (message.status == DeliveryStatus.FAILED && message.kind == MessageKind.SMS) {
                SheetAction(Icons.Filled.Refresh, "Retry sending", act(onRetry))
            }
            SheetAction(Icons.Filled.Delete, "Delete", act(onDelete), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Color.Unspecified) {
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
