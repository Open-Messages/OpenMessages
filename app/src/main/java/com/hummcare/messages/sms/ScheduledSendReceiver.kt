package com.hummcare.messages.sms

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hummcare.messages.appContainer
import com.hummcare.messages.util.launchAsync

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_SCHEDULED) return
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        launchAsync { context.appContainer.scheduler.sendNow(id) }
    }

    companion object {
        const val ACTION_SEND_SCHEDULED = "com.hummcare.messages.SEND_SCHEDULED"
        const val EXTRA_ID = "scheduled_id"
    }
}

/** Alarms are cleared on reboot and app update; re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        launchAsync { context.appContainer.scheduler.restoreAlarms() }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
