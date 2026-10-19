package com.hummcare.messages.ui.components

import android.telephony.SmsMessage
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hummcare.messages.data.model.SimInfo
import com.hummcare.messages.util.TimeFormat
import java.util.Calendar
import java.util.TimeZone

/** Message input bar shared by conversations and the new-message screen. */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    quickResponses: List<String>,
    scheduledAt: Long?,
    onScheduledAtChange: (Long?) -> Unit,
    sims: List<SimInfo>,
    selectedSubscriptionId: Int,
    onSelectSim: (Int) -> Unit,
    modifier: Modifier = Modifier,
    canSend: Boolean = true,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showQuickResponses by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    val counter = remember(text) { segmentCounter(text) }

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (scheduledAt != null) {
                InputChip(
                    selected = true,
                    onClick = { showSchedule = true },
                    label = { Text("Send ${TimeFormat.dateTime(context, scheduledAt)}") },
                    leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close, "Cancel scheduling",
                            Modifier.size(18.dp).clickable { onScheduledAtChange(null) },
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Quick responses") },
                            leadingIcon = { Icon(Icons.Filled.Bolt, null) },
                            onClick = { menuOpen = false; showQuickResponses = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Schedule message") },
                            leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                            onClick = { menuOpen = false; showSchedule = true },
                        )
                    }
                }
                if (sims.size > 1) {
                    val index = sims.indexOfFirst { it.subscriptionId == selectedSubscriptionId }.coerceAtLeast(0)
                    TextButton(
                        onClick = { onSelectSim(sims[(index + 1) % sims.size].subscriptionId) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Icon(Icons.Filled.SimCard, "Switch SIM", Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("${index + 1}")
                    }
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter message") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    counter?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend && text.isNotBlank(),
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        if (scheduledAt != null) {
                            Icon(Icons.Filled.Schedule, "Schedule")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    }

    if (showQuickResponses) {
        QuickResponseSheet(
            responses = quickResponses,
            onDismiss = { showQuickResponses = false },
            onPick = { picked ->
                onTextChange(if (text.isBlank()) picked else "$text $picked")
                showQuickResponses = false
            },
        )
    }
    if (showSchedule) {
        ScheduleMessageDialog(
            initialMillis = scheduledAt,
            onDismiss = { showSchedule = false },
            onConfirm = { at ->
                if (at <= System.currentTimeMillis()) {
                    Toast.makeText(context, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                } else {
                    onScheduledAtChange(at)
                    showSchedule = false
                }
            },
        )
    }
}

/** "remaining/segments" once a message is close to or over one SMS, like most SMS apps show. */
private fun segmentCounter(text: String): String? {
    if (text.isEmpty()) return null
    val (segments, _, remaining) = SmsMessage.calculateLength(text, false)
    return if (segments > 1 || remaining <= 20) "$remaining/$segments" else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickResponseSheet(responses: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Quick responses",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (responses.isEmpty()) {
            Text(
                "Add quick responses in Settings.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(responses) { response ->
                ListItem(
                    headlineContent = { Text(response) },
                    modifier = Modifier.clickable { onPick(response) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMessageDialog(initialMillis: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val context = LocalContext.current
    val start = remember {
        Calendar.getInstance().apply {
            timeInMillis = initialMillis ?: (System.currentTimeMillis() + 60 * 60 * 1000)
        }
    }
    var step by remember { mutableIntStateOf(0) }
    val todayUtc = remember { utcMidnight(Calendar.getInstance()) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = utcMidnight(start),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    val timeState = rememberTimePickerState(
        initialHour = start.get(Calendar.HOUR_OF_DAY),
        initialMinute = start.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context),
    )

    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { step = 1 }, enabled = dateState.selectedDateMillis != null) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Select time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val date = dateState.selectedDateMillis ?: return@TextButton
                    onConfirm(combine(date, timeState.hour, timeState.minute))
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Back") } },
        )
    }
}

/** The date picker works in UTC midnights; convert a local calendar day to that. */
private fun utcMidnight(local: Calendar): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
}.timeInMillis

private fun combine(utcDateMillis: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDateMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute)
    }.timeInMillis
}
