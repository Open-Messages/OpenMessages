package com.hummcare.messages.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.hummcare.messages.appContainer
import com.hummcare.messages.notifications.NotificationHelper
import com.hummcare.messages.util.Otp
import com.hummcare.messages.util.launchAsync

/**
 * Receives SMS_DELIVER, which only the default SMS app gets. Because we're the default app,
 * nobody else writes incoming messages to the provider — we must.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        val first = parts.firstOrNull() ?: return
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val subscriptionId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )
        val container = context.appContainer

        launchAsync {
            // The platform already drops SMS from numbers in BlockedNumberContract; this is a backstop.
            if (container.blocking.isBlocked(address)) return@launchAsync

            container.blocking.matchingPhrase(body)?.let { phrase ->
                container.blocking.recordBlocked(address, body, subscriptionId, "Contains \"$phrase\"")
                return@launchAsync
            }

            val threadId = container.telephony.insertIncomingSms(
                address = address,
                body = body,
                dateSent = first.timestampMillis,
                subscriptionId = subscriptionId,
                serviceCenter = first.serviceCenterAddress,
                protocol = first.protocolIdentifier,
                replyPathPresent = first.isReplyPathPresent,
            )
            if (threadId > 0) NotificationHelper.showThread(context, threadId)

            Otp.find(body)?.let { code ->
                runCatching { container.otpSync.uploadIfEnabled(code, address, body, System.currentTimeMillis()) }
            }
        }
    }
}
