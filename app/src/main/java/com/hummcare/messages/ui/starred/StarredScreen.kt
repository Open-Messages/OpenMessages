package com.hummcare.messages.ui.starred

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.rememberRecipients
import com.hummcare.messages.util.TimeFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val dao = container.db.starredDao()
    val starred by remember { dao.observeAll() }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Starred messages") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (starred.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.StarBorder,
                title = "No starred messages",
                message = "Touch and hold a message, then tap Star to keep it here.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(starred, key = { it.messageKey }) { message ->
                val recipient = rememberRecipients(listOf(message.address)).first()
                ListItem(
                    overlineContent = { Text(if (message.incoming) recipient.displayName else "You → ${recipient.displayName}") },
                    headlineContent = { Text(message.body, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            TimeFormat.dateTime(context, message.date),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    leadingContent = { Avatar(listOf(recipient)) },
                    trailingContent = {
                        IconButton(onClick = { container.applicationScope.launch { dao.delete(listOf(message.messageKey)) } }) {
                            Icon(Icons.Filled.Star, "Unstar", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.clickable { onOpenThread(message.threadId) },
                )
            }
        }
    }
}
