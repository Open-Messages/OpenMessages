package com.hummcare.messages.ui.newmessage

import android.net.Uri
import android.telephony.PhoneNumberUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hummcare.messages.AppContainer
import com.hummcare.messages.data.contacts.ContactSuggestion
import com.hummcare.messages.data.model.Recipient
import com.hummcare.messages.data.model.SimInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NewMessageViewModel(
    private val c: AppContainer,
    initialTo: List<String>,
    initialBody: String,
) : ViewModel() {
    val recipients = MutableStateFlow(initialTo.map(::Recipient))
    val query = MutableStateFlow("")
    val text = MutableStateFlow(initialBody)
    val scheduledAt = MutableStateFlow<Long?>(null)
    val sims = MutableStateFlow<List<SimInfo>>(emptyList())
    val subscriptionId = MutableStateFlow(c.sims.defaultSmsSubscriptionId())
    private var sending = false

    val suggestions: StateFlow<List<ContactSuggestion>> = query
        .debounce(150)
        .mapLatest { q -> if (q.isBlank()) emptyList() else c.contacts.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quickResponses: StateFlow<List<String>> = c.db.quickResponseDao().observeAll()
        .map { list -> list.map { it.text } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            sims.value = withContext(Dispatchers.IO) { c.sims.activeSims() }
            if (initialTo.isNotEmpty()) {
                recipients.value = withContext(Dispatchers.IO) { initialTo.map(c.contacts::lookup) }
            }
        }
    }

    fun setQuery(value: String) {
        // Typing a separator commits what's been typed so far.
        if (value.endsWith(',') || value.endsWith(';')) {
            query.value = value.dropLast(1)
            commitQuery()
        } else {
            query.value = value
        }
    }

    fun isAddress(value: String): Boolean = PhoneNumberUtils.isWellFormedSmsAddress(value.trim())

    /** Adds the typed text as a recipient if it looks like a phone number. */
    fun commitQuery(): Boolean {
        val typed = query.value.trim()
        if (!isAddress(typed)) return false
        addAddress(typed)
        return true
    }

    fun addSuggestion(suggestion: ContactSuggestion) {
        addRecipient(Recipient(suggestion.number, suggestion.name, suggestion.photoUri))
    }

    fun addFromPicker(uri: Uri) {
        viewModelScope.launch { c.contacts.numberFromPickerResult(uri)?.let(::addAddress) }
    }

    fun removeRecipient(recipient: Recipient) = recipients.update { list -> list - recipient }

    private fun addAddress(address: String) {
        addRecipient(Recipient(address))
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) { c.contacts.lookup(address) }
            recipients.update { list -> list.map { if (it.address == address) resolved else it } }
        }
    }

    private fun addRecipient(recipient: Recipient) {
        val normalized = PhoneNumberUtils.normalizeNumber(recipient.address)
        recipients.update { list ->
            if (list.any { PhoneNumberUtils.normalizeNumber(it.address) == normalized }) list else list + recipient
        }
        query.value = ""
    }

    fun send(onSent: (threadId: Long) -> Unit) {
        if (sending) return
        commitQuery()
        val addresses = recipients.value.map { it.address }
        val body = text.value.trim()
        if (addresses.isEmpty() || body.isEmpty()) return
        sending = true
        val at = scheduledAt.value
        val sub = subscriptionId.value
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) { c.telephony.getOrCreateThreadId(addresses) }
            c.applicationScope.launch {
                if (at != null) {
                    c.scheduler.schedule(threadId, addresses, body, at, sub)
                } else {
                    c.smsSender.send(threadId, addresses, body, sub)
                }
            }
            onSent(threadId)
        }
    }
}
