package com.hummcare.messages.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hummcare.messages.AppContainer
import com.hummcare.messages.data.local.PinnedThread
import com.hummcare.messages.data.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationItem(val conversation: Conversation, val pinned: Boolean, val draft: String?)

enum class ConversationFilter { ALL, UNREAD }

data class ConversationListUiState(
    val loading: Boolean = true,
    val all: List<ConversationItem> = emptyList(),
    val pinned: List<ConversationItem> = emptyList(),
    val others: List<ConversationItem> = emptyList(),
    val filter: ConversationFilter = ConversationFilter.ALL,
    val unreadCount: Int = 0,
    val selection: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = !loading && pinned.isEmpty() && others.isEmpty()
    val selectedItems: List<ConversationItem> get() = all.filter { it.conversation.threadId in selection }
}

class ConversationListViewModel(private val container: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(ConversationFilter.ALL)
    private val selection = MutableStateFlow(emptySet<Long>())

    private val items = combine(
        container.telephony.conversations(),
        container.db.pinnedDao().observeIds(),
        container.db.draftDao().observeAll(),
    ) { conversations, pinnedIds, drafts ->
        val pinned = pinnedIds.toSet()
        val draftText = drafts.associate { it.threadId to it.text }
        conversations.map { ConversationItem(it, it.threadId in pinned, draftText[it.threadId]) }
    }

    val state: StateFlow<ConversationListUiState> = combine(items, filter, selection) { all, filter, selected ->
        val visible = when (filter) {
            ConversationFilter.ALL -> all
            ConversationFilter.UNREAD -> all.filter { it.conversation.isUnread }
        }
        val existing = all.mapTo(HashSet()) { it.conversation.threadId }
        ConversationListUiState(
            loading = false,
            all = all,
            pinned = visible.filter { it.pinned },
            others = visible.filterNot { it.pinned },
            filter = filter,
            unreadCount = all.count { it.conversation.isUnread },
            selection = selected.filterTo(HashSet()) { it in existing },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

    fun setFilter(value: ConversationFilter) {
        filter.value = value
    }

    fun toggleSelection(threadId: Long) = selection.update { if (threadId in it) it - threadId else it + threadId }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Pins all selected threads, or unpins them if they're all pinned already. */
    fun togglePinSelected() {
        val selected = state.value.selectedItems
        clearSelection()
        container.applicationScope.launch {
            val dao = container.db.pinnedDao()
            if (selected.all { it.pinned }) {
                dao.unpin(selected.map { it.conversation.threadId })
            } else {
                dao.pin(selected.filterNot { it.pinned }.map { PinnedThread(it.conversation.threadId) })
            }
        }
    }

    fun markSelectedRead() {
        val ids = state.value.selection
        clearSelection()
        container.applicationScope.launch { ids.forEach { container.telephony.markThreadRead(it) } }
    }

    fun markAllRead() {
        container.applicationScope.launch { container.telephony.markAllRead() }
    }

    fun deleteSelected() {
        val ids = state.value.selection
        clearSelection()
        container.applicationScope.launch { container.trash.deleteThreads(ids) }
    }

    fun blockSelected() {
        val addresses = state.value.selectedItems.mapNotNull { it.conversation.recipients.singleOrNull()?.address }
        clearSelection()
        container.applicationScope.launch { addresses.forEach { container.blocking.block(it) } }
    }
}
