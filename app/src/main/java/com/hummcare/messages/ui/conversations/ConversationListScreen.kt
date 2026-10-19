package com.hummcare.messages.ui.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hummcare.messages.appContainer
import com.hummcare.messages.ui.Routes
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.ConfirmDialog
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.SectionHeader
import com.hummcare.messages.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: ConversationListViewModel = viewModel { ConversationListViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val selecting = state.selection.isNotEmpty()

    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    state = state,
                    onClose = vm::clearSelection,
                    onPin = vm::togglePinSelected,
                    onMarkRead = vm::markSelectedRead,
                    onBlock = { confirmBlock = true },
                    onDelete = { confirmDelete = true },
                )
            } else {
                LargeTopAppBar(
                    title = { Text("Messages") },
                    actions = {
                        IconButton(onClick = { onNavigate(Routes.SEARCH) }) { Icon(Icons.Filled.Search, "Search") }
                        OverflowMenu(onMarkAllRead = vm::markAllRead, onNavigate = onNavigate)
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(onClick = onNewMessage) { Icon(Icons.Filled.Edit, "New message") }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                item(key = "filters") {
                    FilterRow(state.filter, state.unreadCount, vm::setFilter)
                }
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Filled.Sms,
                            title = if (state.filter == ConversationFilter.UNREAD) "No unread messages" else "No messages",
                            message = if (state.filter == ConversationFilter.UNREAD) "You're all caught up." else "Tap the pencil button to start a conversation.",
                            modifier = Modifier.padding(top = 64.dp),
                        )
                    }
                }
                if (state.pinned.isNotEmpty()) {
                    item(key = "pinned-header") { SectionHeader("Pinned") }
                    conversationItems(state.pinned, state, vm, onOpenThread)
                    if (state.others.isNotEmpty()) item(key = "others-header") { SectionHeader("Conversations") }
                }
                conversationItems(state.others, state, vm, onOpenThread)
            }
        }
    }

    if (confirmDelete) {
        val count = state.selection.size
        val noun = if (count == 1) "conversation" else "$count conversations"
        ConfirmDialog(
            title = if (settings.trashEnabled) "Move $noun to Trash?" else "Delete $noun?",
            message = if (settings.trashEnabled) {
                "Text messages stay in Trash for 30 days. Multimedia messages are deleted right away."
            } else {
                "This can't be undone."
            },
            confirmLabel = if (settings.trashEnabled) "Move to Trash" else "Delete",
            destructive = true,
            onConfirm = vm::deleteSelected,
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmBlock) {
        ConfirmDialog(
            title = "Block selected numbers?",
            message = "You won't get calls or messages from these numbers. Group conversations aren't affected.",
            confirmLabel = "Block",
            destructive = true,
            onConfirm = vm::blockSelected,
            onDismiss = { confirmBlock = false },
        )
    }
}

private fun LazyListScope.conversationItems(
    items: List<ConversationItem>,
    state: ConversationListUiState,
    vm: ConversationListViewModel,
    onOpenThread: (Long) -> Unit,
) {
    items(items, key = { it.conversation.threadId }) { item ->
        val threadId = item.conversation.threadId
        ConversationRow(
            item = item,
            selected = threadId in state.selection,
            onClick = {
                if (state.selection.isNotEmpty()) vm.toggleSelection(threadId) else onOpenThread(threadId)
            },
            onLongClick = { vm.toggleSelection(threadId) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    state: ConversationListUiState,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onMarkRead: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    val selected = state.selectedItems
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Clear selection") } },
        title = { Text("${state.selection.size} selected") },
        actions = {
            IconButton(onClick = onPin) {
                Icon(Icons.Filled.PushPin, if (selected.all { it.pinned }) "Unpin" else "Pin")
            }
            if (selected.any { it.conversation.isUnread }) {
                IconButton(onClick = onMarkRead) { Icon(Icons.Filled.DoneAll, "Mark as read") }
            }
            if (selected.any { it.conversation.recipients.size == 1 }) {
                IconButton(onClick = onBlock) { Icon(Icons.Filled.Block, "Block") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        },
    )
}

@Composable
private fun OverflowMenu(onMarkAllRead: () -> Unit, onNavigate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Mark all as read") }, onClick = { open = false; onMarkAllRead() })
            DropdownMenuItem(text = { Text("Starred messages") }, onClick = { open = false; onNavigate(Routes.STARRED) })
            DropdownMenuItem(text = { Text("Scheduled messages") }, onClick = { open = false; onNavigate(Routes.SCHEDULED) })
            DropdownMenuItem(text = { Text("Trash") }, onClick = { open = false; onNavigate(Routes.TRASH) })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onNavigate(Routes.SETTINGS) })
        }
    }
}

@Composable
private fun FilterRow(filter: ConversationFilter, unreadCount: Int, onFilter: (ConversationFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter == ConversationFilter.ALL,
            onClick = { onFilter(ConversationFilter.ALL) },
            label = { Text("All") },
        )
        FilterChip(
            selected = filter == ConversationFilter.UNREAD,
            onClick = { onFilter(ConversationFilter.UNREAD) },
            label = { Text(if (unreadCount > 0) "Unread ($unreadCount)" else "Unread") },
        )
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val conversation = item.conversation
    val unread = conversation.isUnread
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(conversation.recipients)
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = colors.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(colors.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.pinned) {
                    Icon(
                        Icons.Filled.PushPin, "Pinned",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp).size(14.dp),
                    )
                }
                Text(
                    TimeFormat.conversation(context, conversation.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) colors.primary else colors.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val snippet = conversation.snippet.ifBlank {
                    if (conversation.hasAttachment) "Multimedia message" else ""
                }
                Text(
                    text = buildAnnotatedString {
                        if (item.draft != null) {
                            withStyle(SpanStyle(color = colors.error)) { append("Draft: ") }
                            append(item.draft)
                        } else {
                            append(snippet)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.hasError) {
                    Icon(
                        Icons.Filled.ErrorOutline, "Failed to send",
                        tint = colors.error,
                        modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    )
                }
                if (conversation.unreadCount > 0) {
                    Badge(modifier = Modifier.padding(start = 8.dp)) { Text("${conversation.unreadCount}") }
                }
            }
        }
    }
}
