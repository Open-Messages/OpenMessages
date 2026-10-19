package com.hummcare.messages.sms

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.hummcare.messages.data.model.Message
import com.hummcare.messages.data.settings.SettingsRepository
import com.hummcare.messages.data.telephony.TelephonyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends SMS through [SmsManager]. Each message is written to the outbox first; [SmsStatusReceiver]
 * then moves it to sent/failed and records delivery reports.
 */
class SmsSender(
    private val context: Context,
    private val telephony: TelephonyRepository,
    private val settings: SettingsRepository,
) {
    /** Group threads are sent as one SMS per recipient, all filed under [threadId]. */
    suspend fun send(threadId: Long, addresses: List<String>, body: String, subscriptionId: Int) =
        withContext(Dispatchers.IO) {
            val deliveryReports = settings.current.deliveryReports
            for (address in addresses) {
                val uri = telephony.insertOutgoingSms(threadId, address, body, subscriptionId, deliveryReports)
                    ?: continue
                dispatch(uri, address, body, subscriptionId, deliveryReports)
            }
        }

    suspend fun retry(message: Message) = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(Sms.CONTENT_URI, message.id)
        val deliveryReports = settings.current.deliveryReports
        telephony.updateSms(
            uri,
            type = Sms.MESSAGE_TYPE_OUTBOX,
            status = if (deliveryReports) Sms.STATUS_PENDING else Sms.STATUS_NONE,
            date = System.currentTimeMillis(),
        )
        dispatch(uri, message.address, message.body, message.subscriptionId, deliveryReports)
    }

    private fun dispatch(uri: Uri, address: String, body: String, subscriptionId: Int, deliveryReports: Boolean) {
        try {
            val manager = smsManager(subscriptionId)
            val parts = manager.divideMessage(body)
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { part ->
                sent += statusIntent(SmsStatusReceiver.ACTION_SENT, uri, part, parts.size)
                if (deliveryReports) delivered += statusIntent(SmsStatusReceiver.ACTION_DELIVERED, uri, part, parts.size)
            }
            manager.sendMultipartTextMessage(address, null, parts, sent, if (deliveryReports) delivered else null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hand SMS to the radio", e)
            telephony.updateSms(uri, type = Sms.MESSAGE_TYPE_FAILED)
        }
    }

    private fun smsManager(subscriptionId: Int): SmsManager {
        val default = context.getSystemService(SmsManager::class.java)
        return if (SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            default.createForSubscriptionId(subscriptionId)
        } else {
            default
        }
    }

    private fun statusIntent(action: String, uri: Uri, part: Int, parts: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .putExtra(SmsStatusReceiver.EXTRA_PART, part)
            .putExtra(SmsStatusReceiver.EXTRA_PARTS, parts)
        // Mutable: the radio fills in the delivery PDU and error code as extras.
        return PendingIntent.getBroadcast(
            context, part, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "SmsSender"
    }
}
