package com.hummcare.messages.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hummcare.messages.AppContainer
import com.hummcare.messages.data.local.Draft
import com.hummcare.messages.data.local.PinnedThread
import com.hummcare.messages.data.local.ScheduledMessage
import com.hummcare.messages.data.local.StarredMessage
import com.hummcare.messages.data.model.Message
import com.hummcare.messages.data.model.MessageKind
import com.hummcare.messages.data.model.Recipient
import com.hummcare.messages.data.model.SimInfo
import com.hummcare.messages.notifications.NotificationHelper
import com.hummcare.messages.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed interface ThreadItem {
    val key: String

    data class Day(val dayStart: Long) : ThreadItem {
        override val key: String get() = "day-$dayStart"
    }

    data class Bubble(
        val message: Message,
        val starred: Boolean,
        /** Show time and status under the bubble (last of a run from the same side). */
        val showMeta: Boolean,
        /** Sender name above incoming bubbles in group threads. */
        val senderName: String?,
    ) : ThreadItem {
        override val key: String get() = message.key
    }
}

data class ThreadUiState(
    val loading: Boolean = true,
    val recipients: List<Recipient> = emptyList(),
    /** Newest first, for a reverse-layout list. */
    val items: List<ThreadItem> = emptyList(),
    val starredKeys: Set<String> = emptySet(),
    val scheduled: List<ScheduledMessage> = emptyList(),
    val pinned: Boolean = false,
    val blocked: Boolean = false,
    val canLoadMore: Boolean = false,
    val sims: List<SimInfo> = emptyList(),
    val selectedSubscriptionId: Int = -1,
) {
    val title: String get() = recipients.joinToString(", ") { it.displayName }.ifEmpty { "Conversation" }
    val single: Recipient? get() = recipients.singleOrNull()
}

private data class ThreadExtras(
    val pinned: Boolean,
    val blocked: Boolean,
    val sims: List<SimInfo>,
    val subscriptionId: Int,
    val limit: Int,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ThreadViewModel(private val c: AppContainer, val threadId: Long) : ViewModel() {
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val recipients = MutableStateFlow<List<Recipient>>(emptyList())
    private val blocked = MutableStateFlow(false)
    private val sims = MutableStateFlow<List<SimInfo>>(emptyList())
    private val subscriptionId = MutableStateFlow(-1)
    private var active = false

    val text = MutableStateFlow("")
    val scheduledAt = MutableStateFlow<Long?>(null)

    val quickResponses: StateFlow<List<String>> = c.db.quickResponseDao().observeAll()
        .map { list -> list.map { it.text } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messages = limit
        .flatMapLatest { c.telephony.messages(threadId, it) }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val extras = combine(
        c.db.pinnedDao().observeIds(), blocked, sims, subscriptionId, limit,
    ) { pinnedIds, blocked, sims, sub, limit ->
        ThreadExtras(threadId in pinnedIds, blocked, sims, sub, limit)
    }

    val state: StateFlow<ThreadUiState> = combine(
        messages,
        recipients,
        c.db.starredDao().observeKeys(threadId),
        c.db.scheduledDao().observeForThread(threadId),
        extras,
    ) { messages, recipients, starred, scheduled, extras ->
        val starredKeys = starred.toSet()
        ThreadUiState(
            loading = false,
            recipients = recipients,
            items = buildItems(messages, starredKeys, recipients),
            starredKeys = starredKeys,
            scheduled = scheduled,
            pinned = extras.pinned,
            blocked = extras.blocked,
            canLoadMore = messages.size >= extras.limit,
            sims = extras.sims,
            selectedSubscriptionId = extras.subscriptionId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    init {
        viewModelScope.launch {
            recipients.value = c.telephony.threadRecipients(threadId)
            refreshBlocked()
        }
        viewModelScope.launch {
            text.value = c.db.draftDao().get(threadId)?.text.orEmpty()
            text.drop(1).debounce(400).collect { saveDraft(it) }
        }
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { c.sims.activeSims() }
            sims.value = available
            // Reply from the SIM the conversation last used, falling back to the system default.
            val lastUsed = messages.first().firstOrNull { m -> available.any { it.subscriptionId == m.subscriptionId } }
            subscriptionId.value = lastUsed?.subscriptionId ?: c.sims.defaultSmsSubscriptionId()
        }
        viewModelScope.launch {
            messages.collect { list -> if (active && list.any { it.incoming && !it.read }) markRead() }
        }
    }

    fun setActive(isActive: Boolean) {
        active = isActive
        if (isActive) {
            NotificationHelper.activeThreadId = threadId
            markRead()
        } else if (NotificationHelper.activeThreadId == threadId) {
            NotificationHelper.activeThreadId = -1
        }
    }

    private fun markRead() {
        c.applicationScope.launch {
            c.telephony.markThreadRead(threadId)
            NotificationHelper.cancelThread(c.appContext, threadId)
        }
    }

    fun onTextChange(value: String) {
        text.value = value
    }

    fun onScheduledAtChange(value: Long?) {
        scheduledAt.value = value
    }

    fun selectSim(id: Int) {
        subscriptionId.value = id
    }

    fun loadMore() {
        limit.value += PAGE_SIZE
    }

    fun send() {
        val body = text.value.trim()
        val addresses = recipients.value.map { it.address }
        if (body.isEmpty() || addresses.isEmpty()) return
        val at = scheduledAt.value
        val sub = subscriptionId.value
        text.value = ""
        scheduledAt.value = null
        c.applicationScope.launch {
            c.db.draftDao().delete(threadId)
            if (at != null) {
                c.scheduler.schedule(threadId, addresses, body, at, sub)
            } else {
                c.smsSender.send(threadId, addresses, body, sub)
            }
        }
    }

    fun retry(message: Message) {
        if (message.kind != MessageKind.SMS) return
        c.applicationScope.launch { c.smsSender.retry(message) }
    }

    fun toggleStar(message: Message) {
        val starred = message.key in state.value.starredKeys
        c.applicationScope.launch {
            val dao = c.db.starredDao()
            if (starred) {
                dao.delete(listOf(message.key))
            } else {
                dao.upsert(
                    StarredMessage(
                        messageKey = message.key,
                        threadId = threadId,
                        address = message.address,
                        body = message.body,
                        date = message.date,
                        incoming = message.incoming,
                    )
                )
            }
        }
    }

    fun delete(message: Message) {
        c.applicationScope.launch { c.trash.deleteMessages(listOf(message)) }
    }

    fun deleteThread() {
        c.applicationScope.launch { c.trash.deleteThreads(listOf(threadId)) }
    }

    fun togglePin() {
        val pinned = state.value.pinned
        c.applicationScope.launch {
            if (pinned) c.db.pinnedDao().unpin(listOf(threadId)) else c.db.pinnedDao().pin(listOf(PinnedThread(threadId)))
        }
    }

    fun block() {
        val address = recipients.value.singleOrNull()?.address ?: return
        viewModelScope.launch {
            c.blocking.block(address)
            refreshBlocked()
        }
    }

    fun unblock() {
        val address = recipients.value.singleOrNull()?.address ?: return
        viewModelScope.launch {
            c.blocking.unblock(address)
            refreshBlocked()
        }
    }

    fun sendScheduledNow(id: Long) {
        c.applicationScope.launch { c.scheduler.sendNow(id) }
    }

    fun cancelScheduled(id: Long) {
        c.applicationScope.launch { c.scheduler.cancel(id) }
    }

    private suspend fun refreshBlocked() {
        val address = recipients.value.singleOrNull()?.address
        blocked.value = address != null && withContext(Dispatchers.IO) { c.blocking.isBlocked(address) }
    }

    private suspend fun saveDraft(value: String) {
        if (value.isBlank()) c.db.draftDao().delete(threadId) else c.db.draftDao().upsert(Draft(threadId, value))
    }

    override fun onCleared() {
        val pending = text.value
        c.applicationScope.launch { saveDraft(pending) }
        if (NotificationHelper.activeThreadId == threadId) NotificationHelper.activeThreadId = -1
    }

    private fun buildItems(messages: List<Message>, starred: Set<String>, recipients: List<Recipient>): List<ThreadItem> {
        val isGroup = recipients.size > 1
        val items = ArrayList<ThreadItem>(messages.size + 8)
        messages.forEachIndexed { index, message ->
            val newer = messages.getOrNull(index - 1)
            val older = messages.getOrNull(index + 1)
            val showMeta = newer == null ||
                newer.incoming != message.incoming ||
                newer.address != message.address ||
                newer.date - message.date > GROUPING_WINDOW_MS ||
                message.status != newer.status
            val firstOfRun = older == null || older.incoming != message.incoming || older.address != message.address
            val senderName = if (isGroup && message.incoming && firstOfRun) {
                recipients.firstOrNull { it.address == message.address }?.displayName ?: message.address
            } else {
                null
            }
            items += ThreadItem.Bubble(message, message.key in starred, showMeta, senderName)
            if (older == null || TimeFormat.startOfDay(older.date) != TimeFormat.startOfDay(message.date)) {
                items += ThreadItem.Day(TimeFormat.startOfDay(message.date))
            }
        }
        return items
    }

    private companion object {
        const val PAGE_SIZE = 200
        val GROUPING_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)
    }
}
