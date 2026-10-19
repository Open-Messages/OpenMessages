package com.hummcare.messages.ui.newmessage

import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.model.Recipient
import com.hummcare.messages.ui.components.Avatar
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.Composer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewMessageScreen(
    initialTo: List<String>,
    initialBody: String,
    onBack: () -> Unit,
    onSent: (Long) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val vm: NewMessageViewModel = viewModel { NewMessageViewModel(container, initialTo, initialBody) }
    val recipients by vm.recipients.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val scheduledAt by vm.scheduledAt.collectAsStateWithLifecycle()
    val quickResponses by vm.quickResponses.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val subscriptionId by vm.subscriptionId.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(vm::addFromPicker)
    }
    LaunchedEffect(Unit) { if (initialTo.isEmpty()) focus.requestFocus() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("New message") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("To", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    recipients.forEach { recipient ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeRecipient(recipient) },
                            label = { Text(recipient.displayName) },
                            avatar = { Avatar(listOf(recipient), size = 24.dp) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { vm.commitQuery() }),
                        modifier = Modifier
                            .widthIn(min = 160.dp)
                            .padding(vertical = 14.dp)
                            .focusRequester(focus),
                        decorationBox = { inner ->
                            if (query.isEmpty() && recipients.isEmpty()) {
                                Text("Name or number", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        },
                    )
                }
                IconButton(onClick = { picker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }) {
                    Icon(Icons.Filled.Contacts, "Choose from contacts")
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                if (vm.isAddress(query)) {
                    item(key = "typed") {
                        ListItem(
                            headlineContent = { Text("Send to ${query.trim()}") },
                            leadingContent = { Avatar(listOf(Recipient(query.trim()))) },
                            modifier = Modifier.clickable { vm.commitQuery() },
                        )
                    }
                }
                items(suggestions, key = { it.number + it.name }) { suggestion ->
                    ListItem(
                        headlineContent = { Text(suggestion.name) },
                        supportingContent = { Text("${suggestion.label} · ${suggestion.number}") },
                        leadingContent = { Avatar(listOf(Recipient(suggestion.number, suggestion.name, suggestion.photoUri))) },
                        modifier = Modifier.clickable { vm.addSuggestion(suggestion) },
                    )
                }
            }
            Composer(
                text = text,
                onTextChange = { vm.text.value = it },
                onSend = { vm.send(onSent) },
                quickResponses = quickResponses,
                scheduledAt = scheduledAt,
                onScheduledAtChange = { vm.scheduledAt.value = it },
                sims = sims,
                selectedSubscriptionId = subscriptionId,
                onSelectSim = { vm.subscriptionId.value = it },
                canSend = recipients.isNotEmpty() || vm.isAddress(query),
            )
        }
    }
}
