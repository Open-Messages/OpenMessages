package com.hummcare.messages.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.hummcare.messages.notifications.NotificationHelper

/**
 * Required for the default SMS role. Downloading MMS needs a WSP/MMS PDU parser, which this app
 * doesn't include yet, so for now the user is told a multimedia message arrived.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        NotificationHelper.showMmsUnsupported(context)
    }
}
