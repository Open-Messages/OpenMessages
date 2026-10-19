package com.hummcare.messages.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.hummcare.messages.appContainer
import com.hummcare.messages.util.SmsIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Handles "Respond via message" from the incoming-call screen. Required for the default SMS role. */
class HeadlessSmsSendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val recipients = SmsIntents.recipients(intent?.data)
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE || text.isNullOrEmpty() || recipients.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val subscriptionId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )
        scope.launch {
            val container = appContainer
            val threadId = container.telephony.getOrCreateThreadId(recipients)
            container.smsSender.send(threadId, recipients, text, subscriptionId)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
