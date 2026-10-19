package com.hummcare.messages.data.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hummcare.messages.data.local.ADDRESS_SEPARATOR
import com.hummcare.messages.data.local.ScheduledDao
import com.hummcare.messages.data.local.ScheduledMessage
import com.hummcare.messages.data.local.addressList
import com.hummcare.messages.sms.ScheduledSendReceiver
import com.hummcare.messages.sms.SmsSender
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MessageScheduler(
    private val context: Context,
    private val dao: ScheduledDao,
    private val sender: SmsSender,
) {
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** Serializes sends so an alarm and an app-start catch-up can't both send the same message. */
    private val sendLock = Mutex()

    fun canScheduleExact(): Boolean = alarms.canScheduleExactAlarms()

    suspend fun schedule(
        threadId: Long,
        addresses: List<String>,
        body: String,
        sendAt: Long,
        subscriptionId: Int,
    ): Long {
        val id = dao.insert(
            ScheduledMessage(
                threadId = threadId,
                addresses = addresses.joinToString(ADDRESS_SEPARATOR),
                body = body,
                sendAt = sendAt,
                subscriptionId = subscriptionId,
            )
        )
        setAlarm(id, sendAt)
        return id
    }

    suspend fun cancel(id: Long) {
        alarms.cancel(pendingIntent(id))
        dao.delete(id)
    }

    suspend fun sendNow(id: Long) = sendLock.withLock {
        val message = dao.get(id) ?: return@withLock
        alarms.cancel(pendingIntent(id))
        dao.delete(id)
        sender.send(message.threadId, message.addressList(), message.body, message.subscriptionId)
    }

    /** Re-arms alarms (they don't survive reboot or force-stop) and sends anything overdue. */
    suspend fun restoreAlarms() {
        val now = System.currentTimeMillis()
        dao.all().forEach { message ->
            if (message.sendAt <= now) sendNow(message.id) else setAlarm(message.id, message.sendAt)
        }
    }

    private fun setAlarm(id: Long, at: Long) {
        val intent = pendingIntent(id)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun pendingIntent(id: Long): PendingIntent {
        val intent = Intent(context, ScheduledSendReceiver::class.java)
            .setAction(ScheduledSendReceiver.ACTION_SEND_SCHEDULED)
            .setData(Uri.parse("scheduled-message://$id"))
            .putExtra(ScheduledSendReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
