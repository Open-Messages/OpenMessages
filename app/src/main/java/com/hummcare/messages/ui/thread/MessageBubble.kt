package com.hummcare.messages.ui.thread

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hummcare.messages.data.local.ScheduledMessage
import com.hummcare.messages.data.model.DeliveryStatus
import com.hummcare.messages.ui.components.ContentUriImage
import com.hummcare.messages.ui.components.linkify
import com.hummcare.messages.util.TimeFormat

private val BubbleMaxWidth = 300.dp

@Composable
fun MessageBubble(item: ThreadItem.Bubble, onLongPress: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val message = item.message
    val outgoing = !message.incoming
    val colors = MaterialTheme.colorScheme
    val container = when {
        message.status == DeliveryStatus.FAILED -> colors.errorContainer
        outgoing -> colors.primary
        else -> colors.surfaceContainerHighest
    }
    val content = when {
        message.status == DeliveryStatus.FAILED -> colors.onErrorContainer
        outgoing -> colors.onPrimary
        else -> colors.onSurface
    }
    val shape = if (outgoing) {
        RoundedCornerShape(20.dp, 20.dp, if (item.showMeta) 4.dp else 20.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, if (item.showMeta) 4.dp else 20.dp)
    }
    val linked = remember(message.body, content) { linkify(message.body, content) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        item.senderName?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp, top = 6.dp),
            )
        }
        Surface(
            color = container,
            contentColor = content,
            shape = shape,
            modifier = Modifier
                .widthIn(max = BubbleMaxWidth)
                .clip(shape)
                .combinedClickable(
                    onClick = { if (message.status == DeliveryStatus.FAILED) onRetry() },
                    onLongClick = onLongPress,
                ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                message.subject?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                message.attachments.forEach { attachment ->
                    if (attachment.isImage) {
                        ContentUriImage(
                            uri = attachment.uri,
                            contentDescription = "Image attachment",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .heightIn(max = 320.dp),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Icon(Icons.Filled.AttachFile, null, Modifier.size(18.dp))
                            Text(attachment.mimeType, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (message.body.isNotEmpty()) {
                    Text(linked, style = MaterialTheme.typography.bodyLarge, color = content)
                }
            }
        }
        if (item.showMeta) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                if (item.starred) Icon(Icons.Filled.Star, "Starred", Modifier.size(12.dp), tint = colors.primary)
                val status = when (message.status) {
                    DeliveryStatus.SENDING -> "Sending…"
                    DeliveryStatus.SENT -> "Sent"
                    DeliveryStatus.DELIVERED -> "Delivered"
                    DeliveryStatus.FAILED -> "Not sent · Tap to retry"
                    DeliveryStatus.NONE -> null
                }
                Text(
                    listOfNotNull(TimeFormat.time(context, message.date), status).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.status == DeliveryStatus.FAILED) colors.error else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ScheduledBubble(message: ScheduledMessage, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.End) {
        Surface(
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            onClick = onClick,
            modifier = Modifier.widthIn(max = BubbleMaxWidth).alpha(0.85f),
        ) {
            Text(message.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Icon(Icons.Filled.Schedule, null, Modifier.size(12.dp), tint = colors.primary)
            Text(
                " Scheduled · ${TimeFormat.dateTime(context, message.sendAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
            )
        }
    }
}

@Composable
fun DayHeader(dayStart: Long) {
    val context = LocalContext.current
    Text(
        TimeFormat.day(context, dayStart),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}
