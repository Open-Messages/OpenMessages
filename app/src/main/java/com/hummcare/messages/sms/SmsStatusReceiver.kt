package com.hummcare.messages.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import com.hummcare.messages.appContainer
import com.hummcare.messages.notifications.NotificationHelper
import com.hummcare.messages.util.launchAsync

/** Receives per-part send results and delivery reports for messages sent by [SmsSender]. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val parts = intent.getIntExtra(EXTRA_PARTS, 1)
        val isLastPart = part == parts - 1
        val result = resultCode
        val telephony = context.appContainer.telephony

        when (intent.action) {
            ACTION_SENT -> launchAsync {
                if (result == Activity.RESULT_OK) {
                    // Any earlier failed part keeps the whole message marked failed.
                    if (isLastPart && telephony.smsType(uri) != Sms.MESSAGE_TYPE_FAILED) {
                        telephony.updateSms(uri, type = Sms.MESSAGE_TYPE_SENT)
                    }
                } else {
                    telephony.updateSms(
                        uri,
                        type = Sms.MESSAGE_TYPE_FAILED,
                        errorCode = intent.getIntExtra("errorCode", result),
                    )
                    val threadId = telephony.threadIdForSms(uri)
                    if (threadId > 0) NotificationHelper.showFailed(context, threadId)
                }
            }

            ACTION_DELIVERED -> if (isLastPart) launchAsync {
                telephony.updateSms(uri, status = deliveryStatus(intent))
            }
        }
    }

    private fun deliveryStatus(intent: Intent): Int {
        val pdu = intent.getByteArrayExtra("pdu") ?: return Sms.STATUS_COMPLETE
        val status = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))?.status
            ?: return Sms.STATUS_COMPLETE
        // GSM TP-Status: 0x00-0x1F done, 0x20-0x3F still trying, 0x40+ permanent failure.
        return when {
            status < 0x20 -> Sms.STATUS_COMPLETE
            status < 0x40 -> Sms.STATUS_PENDING
            else -> Sms.STATUS_FAILED
        }
    }

    companion object {
        const val ACTION_SENT = "com.hummcare.messages.SMS_SENT"
        const val ACTION_DELIVERED = "com.hummcare.messages.SMS_DELIVERED"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"
    }
}
