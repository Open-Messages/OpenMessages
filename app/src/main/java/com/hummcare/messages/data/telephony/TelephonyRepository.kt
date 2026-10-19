package com.hummcare.messages.data.telephony

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.util.Log
import com.hummcare.messages.data.contacts.ContactsRepository
import com.hummcare.messages.data.model.Attachment
import com.hummcare.messages.data.model.Conversation
import com.hummcare.messages.data.model.DeliveryStatus
import com.hummcare.messages.data.model.Message
import com.hummcare.messages.data.model.MessageKind
import com.hummcare.messages.data.model.Recipient
import com.hummcare.messages.data.model.SmsRecord
import com.hummcare.messages.util.observe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Reads and writes the system SMS/MMS provider. Writes only succeed while this app holds the
 * default SMS role.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelephonyRepository(
    private val context: Context,
    private val contacts: ContactsRepository,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val refreshes = MutableStateFlow(0)

    /** Forces observers to reload, e.g. after contacts permission is granted. */
    fun refresh() {
        contacts.invalidate()
        refreshes.update { it + 1 }
    }

    private fun changes(): Flow<Unit> =
        combine(resolver.observe(Sms.CONTENT_URI, Mms.CONTENT_URI, MmsSms.CONTENT_URI), refreshes) { _, _ -> }

    fun conversations(): Flow<List<Conversation>> =
        changes().mapLatest { loadConversations() }.flowOn(Dispatchers.IO)

    fun messages(threadId: Long, limit: Int): Flow<List<Message>> =
        changes().mapLatest { loadMessages(threadId, limit) }.flowOn(Dispatchers.IO)

    // region Conversations

    private fun loadConversations(): List<Conversation> {
        val canonical = loadCanonicalAddresses()
        val unread = loadUnreadCounts()
        val projection = arrayOf(
            Threads._ID, Threads.DATE, Threads.MESSAGE_COUNT, Threads.RECIPIENT_IDS,
            Threads.SNIPPET, Threads.READ, Threads.HAS_ATTACHMENT, Threads.ERROR,
        )
        return query(THREADS_URI, projection, "${Threads.MESSAGE_COUNT} > 0", null, "${Threads.DATE} DESC") { c ->
            buildList {
                while (c.moveToNext()) {
                    val threadId = c.getLong(0)
                    add(
                        Conversation(
                            threadId = threadId,
                            recipients = recipientsFor(c.getString(3), canonical),
                            snippet = c.getString(4).orEmpty(),
                            date = c.getLong(1),
                            read = c.getInt(5) != 0,
                            unreadCount = unread[threadId] ?: 0,
                            messageCount = c.getInt(2),
                            hasAttachment = c.getInt(6) != 0,
                            hasError = c.getInt(7) != 0,
                        )
                    )
                }
            }
        }.orEmpty()
    }

    suspend fun threadRecipients(threadId: Long): List<Recipient> = withContext(Dispatchers.IO) {
        val canonical = loadCanonicalAddresses()
        query(THREADS_URI, arrayOf(Threads.RECIPIENT_IDS), "${Threads._ID} = ?", arrayOf(threadId.toString())) { c ->
            if (c.moveToFirst()) recipientsFor(c.getString(0), canonical) else null
        }.orEmpty()
    }

    fun getOrCreateThreadId(addresses: Collection<String>): Long =
        Threads.getOrCreateThreadId(context, addresses.toSet())

    private fun recipientsFor(recipientIds: String?, canonical: Map<Long, String>): List<Recipient> =
        recipientIds.orEmpty()
            .split(' ')
            .mapNotNull { it.toLongOrNull()?.let(canonical::get) }
            .filter { it.isNotBlank() }
            .map(contacts::lookup)

    private fun loadCanonicalAddresses(): Map<Long, String> =
        query(CANONICAL_ADDRESSES_URI, arrayOf("_id", "address")) { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty()) }
        }.orEmpty()

    private fun loadUnreadCounts(): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        query(Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID), "${Sms.READ} = 0 AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}") { c ->
            while (c.moveToNext()) counts.merge(c.getLong(0), 1) { a, b -> a + b }
        }
        query(Mms.CONTENT_URI, arrayOf(Mms.THREAD_ID), "${Mms.READ} = 0 AND ${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX}") { c ->
            while (c.moveToNext()) counts.merge(c.getLong(0), 1) { a, b -> a + b }
        }
        return counts
    }

    // endregion

    // region Messages

    private fun loadMessages(threadId: Long, limit: Int): List<Message> {
        val args = arrayOf(threadId.toString())
        val sms = querySms("${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", args, limit)
        val mms = queryMms("${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS}", args, limit)
        return (sms + mms).sortedByDescending { it.date }.take(limit)
    }

    /** Unread incoming messages in [threadId], oldest first, for notifications. */
    suspend fun unreadIncoming(threadId: Long, limit: Int): List<Message> = withContext(Dispatchers.IO) {
        val args = arrayOf(threadId.toString())
        val sms = querySms("${Sms.THREAD_ID} = ? AND ${Sms.READ} = 0 AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}", args, limit)
        val mms = queryMms("${Mms.THREAD_ID} = ? AND ${Mms.READ} = 0 AND ${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX}", args, limit)
        (sms + mms).sortedByDescending { it.date }.take(limit).reversed()
    }

    suspend fun searchMessages(text: String, limit: Int = 200): List<Message> = withContext(Dispatchers.IO) {
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        querySms("${Sms.BODY} LIKE ? ESCAPE '\\'", arrayOf("%$escaped%"), limit)
    }

    private fun querySms(selection: String?, args: Array<String>?, limit: Int): List<Message> =
        query(Sms.CONTENT_URI, SMS_PROJECTION, selection, args, "${Sms.DATE} DESC") { c ->
            buildList { while (size < limit && c.moveToNext()) add(c.toSmsMessage()) }
        }.orEmpty()

    private fun Cursor.toSmsMessage(): Message {
        val type = getInt(5)
        return Message(
            id = getLong(0),
            kind = MessageKind.SMS,
            threadId = getLong(1),
            address = getString(2).orEmpty(),
            body = getString(3).orEmpty(),
            date = getLong(4),
            incoming = type == Sms.MESSAGE_TYPE_INBOX,
            status = smsStatus(type, getInt(6)),
            read = getInt(7) != 0,
            subscriptionId = getInt(8),
        )
    }

    private fun smsStatus(type: Int, status: Int): DeliveryStatus = when (type) {
        Sms.MESSAGE_TYPE_OUTBOX, Sms.MESSAGE_TYPE_QUEUED -> DeliveryStatus.SENDING
        Sms.MESSAGE_TYPE_FAILED -> DeliveryStatus.FAILED
        Sms.MESSAGE_TYPE_SENT -> if (status == Sms.STATUS_COMPLETE) DeliveryStatus.DELIVERED else DeliveryStatus.SENT
        else -> DeliveryStatus.NONE
    }

    private data class MmsRow(
        val id: Long,
        val threadId: Long,
        val date: Long,
        val box: Int,
        val read: Boolean,
        val subscriptionId: Int,
        val messageType: Int,
        val subject: String?,
    )

    private fun queryMms(selection: String?, args: Array<String>?, limit: Int): List<Message> {
        val projection = arrayOf(
            Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX, Mms.READ,
            Mms.SUBSCRIPTION_ID, Mms.MESSAGE_TYPE, Mms.SUBJECT,
        )
        val rows = query(Mms.CONTENT_URI, projection, selection, args, "${Mms.DATE} DESC") { c ->
            buildList {
                while (size < limit && c.moveToNext()) {
                    add(
                        MmsRow(
                            id = c.getLong(0),
                            threadId = c.getLong(1),
                            date = c.getLong(2) * 1000, // MMS dates are stored in seconds.
                            box = c.getInt(3),
                            read = c.getInt(4) != 0,
                            subscriptionId = c.getInt(5),
                            messageType = c.getInt(6),
                            subject = c.getString(7),
                        )
                    )
                }
            }
        }.orEmpty()

        return rows.map { row ->
            val incoming = row.box == Mms.MESSAGE_BOX_INBOX
            val (text, attachments) = loadMmsParts(row.id)
            val body = if (row.messageType == MMS_NOTIFICATION_IND) {
                "Multimedia message (not downloaded)"
            } else {
                text
            }
            Message(
                id = row.id,
                kind = MessageKind.MMS,
                threadId = row.threadId,
                address = loadMmsAddress(row.id, incoming),
                body = body,
                date = row.date,
                incoming = incoming,
                status = mmsStatus(row.box),
                read = row.read,
                subscriptionId = row.subscriptionId,
                attachments = attachments,
                subject = row.subject?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun loadMmsParts(messageId: Long): Pair<String, List<Attachment>> {
        val text = StringBuilder()
        val attachments = mutableListOf<Attachment>()
        query(MMS_PART_URI, arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(messageId.toString())) { c ->
            while (c.moveToNext()) {
                val type = c.getString(1).orEmpty()
                when {
                    type == "application/smil" -> Unit
                    type.startsWith("text/") -> c.getString(2)?.let {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(it)
                    }
                    else -> attachments += Attachment(ContentUris.withAppendedId(MMS_PART_URI, c.getLong(0)), type)
                }
            }
        }
        return text.toString() to attachments
    }

    private fun loadMmsAddress(messageId: Long, incoming: Boolean): String {
        val wanted = if (incoming) PDU_FROM else PDU_TO
        return query(Uri.parse("content://mms/$messageId/addr"), arrayOf("address"), "type = $wanted") { c ->
            var found: String? = null
            while (found == null && c.moveToNext()) {
                found = c.getString(0)?.takeIf { it.isNotBlank() && it != "insert-address-token" }
            }
            found
        }.orEmpty()
    }

    private fun mmsStatus(box: Int): DeliveryStatus = when (box) {
        Mms.MESSAGE_BOX_OUTBOX -> DeliveryStatus.SENDING
        Mms.MESSAGE_BOX_FAILED -> DeliveryStatus.FAILED
        Mms.MESSAGE_BOX_SENT -> DeliveryStatus.SENT
        else -> DeliveryStatus.NONE
    }

    // endregion

    // region Writes

    /** Stores an incoming SMS and returns its thread id, or -1 on failure. */
    fun insertIncomingSms(
        address: String,
        body: String,
        dateSent: Long,
        subscriptionId: Int,
        serviceCenter: String?,
        protocol: Int,
        replyPathPresent: Boolean,
    ): Long {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.DATE_SENT, dateSent)
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
            put(Sms.SUBSCRIPTION_ID, subscriptionId)
            put(Sms.SERVICE_CENTER, serviceCenter)
            put(Sms.PROTOCOL, protocol)
            put(Sms.REPLY_PATH_PRESENT, if (replyPathPresent) 1 else 0)
        }
        val uri = write { resolver.insert(Sms.Inbox.CONTENT_URI, values) } ?: return -1
        return threadIdForSms(uri)
    }

    /** Adds a message to the outbox; the returned URI is updated as send/delivery results arrive. */
    fun insertOutgoingSms(
        threadId: Long,
        address: String,
        body: String,
        subscriptionId: Int,
        deliveryReport: Boolean,
    ): Uri? {
        val values = ContentValues().apply {
            put(Sms.THREAD_ID, threadId)
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.SUBSCRIPTION_ID, subscriptionId)
            put(Sms.STATUS, if (deliveryReport) Sms.STATUS_PENDING else Sms.STATUS_NONE)
        }
        return write { resolver.insert(Sms.Outbox.CONTENT_URI, values) }
    }

    fun updateSms(uri: Uri, type: Int? = null, status: Int? = null, errorCode: Int? = null, date: Long? = null) {
        val values = ContentValues().apply {
            type?.let { put(Sms.TYPE, it) }
            status?.let { put(Sms.STATUS, it) }
            errorCode?.let { put(Sms.ERROR_CODE, it) }
            date?.let { put(Sms.DATE, it) }
        }
        if (values.size() > 0) write { resolver.update(uri, values, null, null) }
    }

    fun smsType(uri: Uri): Int? =
        query(uri, arrayOf(Sms.TYPE)) { c -> if (c.moveToFirst()) c.getInt(0) else null }

    fun threadIdForSms(uri: Uri): Long =
        query(uri, arrayOf(Sms.THREAD_ID)) { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: -1

    suspend fun markThreadRead(threadId: Long) = withContext(Dispatchers.IO) {
        markRead("${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()))
    }

    suspend fun markAllRead() = withContext(Dispatchers.IO) { markRead(null, null) }

    private fun markRead(threadSelection: String?, args: Array<String>?) {
        val unread = "(${Sms.READ} = 0 OR ${Sms.SEEN} = 0)"
        val selection = if (threadSelection == null) unread else "$threadSelection AND $unread"
        val values = ContentValues().apply {
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        write { resolver.update(Sms.CONTENT_URI, values, selection, args) }
        write { resolver.update(Mms.CONTENT_URI, values, selection, args) }
    }

    fun smsRecord(id: Long): SmsRecord? =
        smsRecords("${Sms._ID} = ?", arrayOf(id.toString())).firstOrNull()

    fun smsRecordsForThread(threadId: Long): List<SmsRecord> =
        smsRecords("${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}", arrayOf(threadId.toString()))

    private fun smsRecords(selection: String, args: Array<String>): List<SmsRecord> {
        val projection = arrayOf(
            Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.DATE_SENT, Sms.TYPE, Sms.READ, Sms.SUBSCRIPTION_ID,
        )
        return query(Sms.CONTENT_URI, projection, selection, args) { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SmsRecord(
                            threadId = c.getLong(0),
                            address = c.getString(1).orEmpty(),
                            body = c.getString(2).orEmpty(),
                            date = c.getLong(3),
                            dateSent = c.getLong(4),
                            type = c.getInt(5),
                            read = c.getInt(6) != 0,
                            subscriptionId = c.getInt(7),
                        )
                    )
                }
            }
        }.orEmpty()
    }

    /** Re-inserts an SMS (from Trash or the blocked list). The provider assigns its thread. */
    fun restoreSms(record: SmsRecord): Uri? {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, record.address)
            put(Sms.BODY, record.body)
            put(Sms.DATE, record.date)
            put(Sms.DATE_SENT, record.dateSent)
            put(Sms.TYPE, record.type)
            put(Sms.READ, if (record.read) 1 else 0)
            put(Sms.SEEN, 1)
            put(Sms.SUBSCRIPTION_ID, record.subscriptionId)
        }
        return write { resolver.insert(Sms.CONTENT_URI, values) }
    }

    fun deleteSms(id: Long) {
        write { resolver.delete(ContentUris.withAppendedId(Sms.CONTENT_URI, id), null, null) }
    }

    fun deleteMms(id: Long) {
        write { resolver.delete(ContentUris.withAppendedId(Mms.CONTENT_URI, id), null, null) }
    }

    fun deleteThread(threadId: Long) {
        write { resolver.delete(ContentUris.withAppendedId(Threads.CONTENT_URI, threadId), null, null) }
    }

    // endregion

    private inline fun <T> query(
        uri: Uri,
        projection: Array<String>?,
        selection: String? = null,
        args: Array<String>? = null,
        sort: String? = null,
        block: (Cursor) -> T,
    ): T? = try {
        resolver.query(uri, projection, selection, args, sort)?.use(block)
    } catch (e: SecurityException) {
        Log.w(TAG, "Missing permission to read $uri", e)
        null
    } catch (e: Exception) {
        Log.w(TAG, "Query failed for $uri", e)
        null
    }

    private inline fun <T> write(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Provider write failed", e)
        null
    }

    private companion object {
        const val TAG = "TelephonyRepository"
        val THREADS_URI: Uri = Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        val CANONICAL_ADDRESSES_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        val MMS_PART_URI: Uri = Uri.parse("content://mms/part")
        val SMS_PROJECTION = arrayOf(
            Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.STATUS, Sms.READ, Sms.SUBSCRIPTION_ID,
        )
        const val MMS_NOTIFICATION_IND = 130
        const val PDU_FROM = 137
        const val PDU_TO = 151
    }
}
