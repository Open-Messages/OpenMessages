package com.hummcare.messages.data.trash

import com.hummcare.messages.data.local.LocalDatabase
import com.hummcare.messages.data.local.TrashedMessage
import com.hummcare.messages.data.model.Message
import com.hummcare.messages.data.model.MessageKind
import com.hummcare.messages.data.model.SmsRecord
import com.hummcare.messages.data.settings.SettingsRepository
import com.hummcare.messages.data.telephony.TelephonyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Deletes go through here. With Trash enabled, SMS are copied to the local database before being
 * removed from the provider, and can be restored for [RETENTION_DAYS] days. MMS are deleted
 * immediately because their parts can't be reconstructed from a copy.
 */
class TrashManager(
    private val telephony: TelephonyRepository,
    private val db: LocalDatabase,
    private val settings: SettingsRepository,
) {
    private val trashDao = db.trashDao()

    suspend fun deleteMessages(messages: List<Message>) = withContext(Dispatchers.IO) {
        val keepCopy = settings.current.trashEnabled
        val copies = mutableListOf<TrashedMessage>()
        for (message in messages) {
            when (message.kind) {
                MessageKind.SMS -> {
                    if (keepCopy) telephony.smsRecord(message.id)?.let { copies += it.toTrashed() }
                    telephony.deleteSms(message.id)
                }
                MessageKind.MMS -> telephony.deleteMms(message.id)
            }
        }
        if (copies.isNotEmpty()) trashDao.insertAll(copies)
        db.starredDao().delete(messages.map { it.key })
    }

    suspend fun deleteThreads(threadIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val keepCopy = settings.current.trashEnabled
        for (threadId in threadIds) {
            if (keepCopy) {
                val copies = telephony.smsRecordsForThread(threadId).map { it.toTrashed() }
                if (copies.isNotEmpty()) trashDao.insertAll(copies)
            }
            telephony.deleteThread(threadId)
            db.draftDao().delete(threadId)
            db.starredDao().deleteForThread(threadId)
        }
        db.pinnedDao().unpin(threadIds.toList())
    }

    suspend fun restore(items: List<TrashedMessage>) = withContext(Dispatchers.IO) {
        items.forEach { telephony.restoreSms(it.toRecord()) }
        trashDao.delete(items.map { it.id })
    }

    suspend fun restoreAll() = restore(trashDao.all())

    suspend fun deleteForever(ids: List<Long>) = trashDao.delete(ids)

    suspend fun empty() = trashDao.clear()

    suspend fun purgeExpired() {
        trashDao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS))
    }

    companion object {
        const val RETENTION_DAYS = 30L
    }
}

private fun SmsRecord.toTrashed() = TrashedMessage(
    threadId = threadId,
    address = address,
    body = body,
    date = date,
    dateSent = dateSent,
    type = type,
    read = read,
    subscriptionId = subscriptionId,
)

private fun TrashedMessage.toRecord() = SmsRecord(
    threadId = threadId,
    address = address,
    body = body,
    date = date,
    dateSent = dateSent,
    type = type,
    read = read,
    subscriptionId = subscriptionId,
)
