package com.hummcare.messages.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.local.QuickResponse
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.TextInputDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickResponsesScreen(onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val dao = container.db.quickResponseDao()
    val scope = container.applicationScope
    val responses by remember { dao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickResponse?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Quick responses") }, navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add quick response") }
        },
    ) { padding ->
        if (responses.isEmpty()) {
            EmptyState(
                Icons.Filled.Bolt, "No quick responses",
                "Add replies you send often, then insert them from the + menu in any conversation.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(responses, key = { it.id }) { response ->
                ListItem(
                    headlineContent = { Text(response.text) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { dao.delete(response) } }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                    modifier = Modifier.clickable { editing = response },
                )
            }
        }
    }

    if (adding) {
        TextInputDialog(
            title = "Add quick response",
            label = "Response",
            confirmLabel = "Add",
            onConfirm = { text -> scope.launch { dao.insert(QuickResponse(text = text, position = dao.nextPosition())) } },
            onDismiss = { adding = false },
        )
    }
    editing?.let { response ->
        TextInputDialog(
            title = "Edit quick response",
            label = "Response",
            initial = response.text,
            onConfirm = { text -> scope.launch { dao.update(response.copy(text = text)) } },
            onDismiss = { editing = null },
        )
    }
}
