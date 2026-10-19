package com.hummcare.messages.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hummcare.messages.AppContainer
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.model.Conversation
import com.hummcare.messages.data.model.Message
import com.hummcare.messages.data.model.Recipient
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.SectionHeader
import com.hummcare.messages.ui.components.highlight
import com.hummcare.messages.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class MessageHit(val message: Message, val title: String, val recipients: List<Recipient>)

data class SearchResults(
    val query: String = "",
    val conversations: List<Conversation> = emptyList(),
    val messages: List<MessageHit> = emptyList(),
) {
    val isEmpty: Boolean get() = conversations.isEmpty() && messages.isEmpty()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    private var conversations: List<Conversation>? = null

    val results = query
        .debounce(250)
        .mapLatest { raw ->
            val q = raw.trim()
            if (q.isEmpty()) return@mapLatest SearchResults()
            val all = conversations ?: c.telephony.conversations().first().also { conversations = it }
            val byThread = all.associateBy { it.threadId }
            SearchResults(
                query = q,
                conversations = all.filter { conversation ->
                    conversation.recipients.any { it.displayName.contains(q, ignoreCase = true) || it.address.contains(q) }
                },
                messages = c.telephony.searchMessages(q).map { message ->
                    val conversation = byThread[message.threadId]
                    MessageHit(
                        message = message,
                        title = conversation?.title ?: message.address,
                        recipients = conversation?.recipients ?: listOf(Recipient(message.address)),
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val highlightColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(onBack)
                    TextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        placeholder = { Text("Search messages and contacts") },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Clear") }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
            }
        },
    ) { padding ->
        if (results.query.isNotEmpty() && results.isEmpty) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = "No results",
                message = "Nothing matches \"${results.query}\".",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (results.conversations.isNotEmpty()) {
                item(key = "conversations-header") { SectionHeader("Conversations") }
                items(results.conversations, key = { "c-${it.threadId}" }) { conversation ->
                    ListItem(
                        headlineContent = { Text(highlight(conversation.title, results.query, highlightColor), maxLines = 1) },
                        supportingContent = { Text(conversation.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Avatar(conversation.recipients) },
                        modifier = Modifier.clickable { onOpenThread(conversation.threadId) },
                    )
                }
            }
            if (results.messages.isNotEmpty()) {
                item(key = "messages-header") { SectionHeader("Messages") }
                items(results.messages, key = { "m-${it.message.key}" }) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                highlight(excerpt(hit.message.body, results.query), results.query, highlightColor),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { Avatar(hit.recipients) },
                        trailingContent = {
                            Text(TimeFormat.conversation(context, hit.message.date), style = MaterialTheme.typography.labelMedium)
                        },
                        modifier = Modifier.clickable { onOpenThread(hit.message.threadId) },
                    )
                }
            }
        }
    }
}

/** Starts the preview shortly before the match so it's visible in two lines. */
private fun excerpt(body: String, query: String): String {
    val index = body.indexOf(query, ignoreCase = true)
    return if (index > 40) "…" + body.substring(index - 30) else body
}
