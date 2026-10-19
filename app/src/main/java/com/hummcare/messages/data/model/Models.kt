package com.hummcare.messages.data.model

import android.net.Uri

data class Recipient(
    val address: String,
    val name: String? = null,
    val photoUri: Uri? = null,
    val contactUri: Uri? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address
    val isSaved: Boolean get() = contactUri != null
}

data class Conversation(
    val threadId: Long,
    val recipients: List<Recipient>,
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val unreadCount: Int,
    val messageCount: Int,
    val hasAttachment: Boolean,
    val hasError: Boolean,
) {
    val title: String
        get() = recipients.joinToString(", ") { it.displayName }.ifEmpty { "Unknown sender" }
    val isUnread: Boolean get() = unreadCount > 0 || !read
}

enum class MessageKind { SMS, MMS }

enum class DeliveryStatus { NONE, SENDING, SENT, DELIVERED, FAILED }

data class Attachment(val uri: Uri, val mimeType: String) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

data class Message(
    val id: Long,
    val kind: MessageKind,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val incoming: Boolean,
    val status: DeliveryStatus,
    val read: Boolean,
    val subscriptionId: Int,
    val attachments: List<Attachment> = emptyList(),
    val subject: String? = null,
) {
    /** Stable identity across SMS and MMS tables, which have overlapping row ids. */
    val key: String get() = "${kind.name.lowercase()}:$id"
}

/** A raw SMS row, used when moving messages to and from the local Trash. */
data class SmsRecord(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,
    val read: Boolean,
    val subscriptionId: Int,
)

data class SimInfo(val subscriptionId: Int, val slotIndex: Int, val label: String)
