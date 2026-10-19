package com.hummcare.messages.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.hummcare.messages.R
import com.hummcare.messages.appContainer
import com.hummcare.messages.ui.MainActivity
import com.hummcare.messages.util.Otp

object NotificationHelper {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_ALERTS = "alerts"
    private const val TAG_THREAD = "thread"
    private const val TAG_FAILED = "failed"
    private const val ID_MMS = 1

    /** Thread currently on screen; incoming messages there don't notify. */
    @Volatile
    var activeThreadId: Long = -1L

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New incoming messages"
                },
                NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Messages that failed to send and other alerts"
                },
            )
        )
    }

    /** Posts or refreshes the notification for [threadId] from its current unread messages. */
    suspend fun showThread(context: Context, threadId: Long) {
        val container = context.appContainer
        val settings = container.settings.current
        if (!settings.notificationsEnabled || threadId == activeThreadId || !canPost(context)) return

        val unread = container.telephony.unreadIncoming(threadId, limit = 8)
        if (unread.isEmpty()) {
            cancelThread(context, threadId)
            return
        }
        val recipients = container.telephony.threadRecipients(threadId)
        val title = recipients.joinToString { it.displayName }.ifEmpty { unread.last().address }
        val isGroup = recipients.size > 1

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setGroupConversation(isGroup)
        if (isGroup) style.conversationTitle = title
        for (message in unread) {
            val sender = container.contacts.lookup(message.address)
            val text = if (settings.showPreview) message.body.ifBlank { "Multimedia message" } else "New message"
            style.addMessage(text, message.date, Person.Builder().setName(sender.displayName).setKey(sender.address).build())
        }

        val last = unread.last()
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(if (settings.showPreview) last.body else "New message")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setWhen(last.date)
            .setNumber(unread.size)
            .setContentIntent(openThreadIntent(context, threadId))
            .addAction(replyAction(context, threadId, last.subscriptionId))
            .addAction(
                NotificationCompat.Action.Builder(
                    0, "Mark as read",
                    actionIntent(context, NotificationActionReceiver.ACTION_MARK_READ, threadId),
                )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build()
            )
        if (settings.copyCodeAction && settings.showPreview) {
            Otp.find(last.body)?.let { code ->
                val intent = actionIntent(context, NotificationActionReceiver.ACTION_COPY_CODE, threadId) {
                    putExtra(NotificationActionReceiver.EXTRA_CODE, code)
                }
                builder.addAction(0, "Copy $code", intent)
            }
        }
        post(context, TAG_THREAD, threadId.toInt(), builder.build())
    }

    suspend fun showFailed(context: Context, threadId: Long) {
        if (!canPost(context)) return
        val names = context.appContainer.telephony.threadRecipients(threadId).joinToString { it.displayName }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Message not sent")
            .setContentText(if (names.isEmpty()) "Tap to try again." else "Couldn't send to $names. Tap to try again.")
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openThreadIntent(context, threadId))
            .build()
        post(context, TAG_FAILED, threadId.toInt(), notification)
    }

    fun showMmsUnsupported(context: Context) {
        if (!canPost(context)) return
        val text = "This app can't download MMS yet. Ask the sender to resend as a text message."
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Multimedia message received")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        post(context, null, ID_MMS, notification)
    }

    fun cancelThread(context: Context, threadId: Long) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(TAG_THREAD, threadId.toInt())
        manager.cancel(TAG_FAILED, threadId.toInt())
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked in canPost().
    private fun post(context: Context, tag: String?, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    private fun openThreadIntent(context: Context, threadId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, threadId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun replyAction(context: Context, threadId: Long, subscriptionId: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Reply").build()
        // Mutable so the system can attach the RemoteInput result.
        val intent = actionIntent(context, NotificationActionReceiver.ACTION_REPLY, threadId, mutable = true) {
            putExtra(NotificationActionReceiver.EXTRA_SUB_ID, subscriptionId)
        }
        return NotificationCompat.Action.Builder(0, "Reply", intent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionIntent(
        context: Context,
        action: String,
        threadId: Long,
        mutable: Boolean = false,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            .apply(extras)
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, threadId.toInt(), intent,
            mutability or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
