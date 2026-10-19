package com.hummcare.messages.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import androidx.core.app.RemoteInput
import com.hummcare.messages.appContainer
import com.hummcare.messages.util.copyToClipboard
import com.hummcare.messages.util.launchAsync

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        if (threadId < 0) return
        val container = context.appContainer

        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val subscriptionId = intent.getIntExtra(EXTRA_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                launchAsync {
                    val recipients = container.telephony.threadRecipients(threadId).map { it.address }
                    if (recipients.isNotEmpty()) container.smsSender.send(threadId, recipients, text, subscriptionId)
                    container.telephony.markThreadRead(threadId)
                    NotificationHelper.cancelThread(context, threadId)
                }
            }

            ACTION_MARK_READ -> launchAsync {
                container.telephony.markThreadRead(threadId)
                NotificationHelper.cancelThread(context, threadId)
            }

            ACTION_COPY_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE) ?: return
                context.copyToClipboard("Verification code", code)
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.hummcare.messages.REPLY"
        const val ACTION_MARK_READ = "com.hummcare.messages.MARK_READ"
        const val ACTION_COPY_CODE = "com.hummcare.messages.COPY_CODE"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_SUB_ID = "subscription_id"
        const val EXTRA_CODE = "code"
        const val KEY_REPLY = "reply_text"
    }
}
