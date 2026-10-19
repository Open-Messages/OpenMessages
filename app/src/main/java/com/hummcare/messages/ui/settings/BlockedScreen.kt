package com.hummcare.messages.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.local.BlockedMessage
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.TextInputDialog
import com.hummcare.messages.ui.components.rememberRecipients
import com.hummcare.messages.util.TimeFormat
import kotlinx.coroutines.launch

private val Tabs = listOf("Numbers", "Phrases", "Blocked messages")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val blocking = container.blocking
    val scope = container.applicationScope
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<BlockedMessage?>(null) }
    val numbers by remember { blocking.blockedNumbers() }.collectAsStateWithLifecycle(emptyList())
    val phrases by blocking.phrases.collectAsStateWithLifecycle(emptyList())
    val messages by blocking.blockedMessages.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Block numbers and spam") }, navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = {
            if (tab < 2) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, if (tab == 0) "Block a number" else "Block a phrase")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tabs.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> if (numbers.isEmpty()) {
                    EmptyState(
                        Icons.Filled.Block, "No blocked numbers",
                        if (blocking.canBlock()) "Calls and messages from blocked numbers are rejected." else "Number blocking isn't available for this user.",
                    )
                } else {
                    LazyColumn {
                        items(numbers, key = { it.id }) { number ->
                            val recipient = rememberRecipients(listOf(number.number)).first()
                            ListItem(
                                headlineContent = { Text(recipient.displayName) },
                                supportingContent = if (recipient.isSaved) ({ Text(number.number) }) else null,
                                leadingContent = { Avatar(listOf(recipient)) },
                                trailingContent = {
                                    IconButton(onClick = { scope.launch { blocking.unblock(number.number) } }) {
                                        Icon(Icons.Filled.Close, "Unblock")
                                    }
                                },
                            )
                        }
                    }
                }

                1 -> if (phrases.isEmpty()) {
                    EmptyState(
                        Icons.Filled.TextFields, "No blocked phrases",
                        "Texts containing a blocked phrase go to Blocked messages instead of your inbox.",
                    )
                } else {
                    LazyColumn {
                        items(phrases, key = { it.phrase }) { phrase ->
                            ListItem(
                                headlineContent = { Text(phrase.phrase) },
                                trailingContent = {
                                    IconButton(onClick = { scope.launch { blocking.removePhrase(phrase.phrase) } }) {
                                        Icon(Icons.Filled.Close, "Remove")
                                    }
                                },
                            )
                        }
                    }
                }

                else -> if (messages.isEmpty()) {
                    EmptyState(
                        Icons.Filled.VerifiedUser, "No blocked messages",
                        "Messages stopped by a blocked phrase appear here.",
                    )
                } else {
                    LazyColumn {
                        items(messages, key = { it.id }) { message ->
                            val recipient = rememberRecipients(listOf(message.address)).first()
                            ListItem(
                                overlineContent = { Text("${recipient.displayName} · ${message.reason}") },
                                headlineContent = { Text(message.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(TimeFormat.dateTime(context, message.date), style = MaterialTheme.typography.labelMedium)
                                },
                                modifier = Modifier.clickable { target = message },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        if (tab == 0) {
            TextInputDialog(
                title = "Block number",
                label = "Phone number",
                confirmLabel = "Block",
                keyboardType = KeyboardType.Phone,
                onConfirm = { number -> scope.launch { blocking.block(number) } },
                onDismiss = { showAdd = false },
            )
        } else {
            TextInputDialog(
                title = "Block phrase",
                label = "Word or phrase",
                confirmLabel = "Add",
                onConfirm = { phrase -> scope.launch { blocking.addPhrase(phrase) } },
                onDismiss = { showAdd = false },
            )
        }
    }
    target?.let { message ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("Blocked message") },
            text = { Text(message.body) },
            confirmButton = {
                TextButton(onClick = { scope.launch { blocking.restore(message) }; target = null }) {
                    Text("Move to inbox")
                }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { blocking.deleteBlockedMessage(message.id) }; target = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
