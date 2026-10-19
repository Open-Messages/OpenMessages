package com.hummcare.messages.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.ui.Routes
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.NavRow
import com.hummcare.messages.ui.components.SectionHeader
import com.hummcare.messages.ui.components.SwitchRow
import com.hummcare.messages.util.DefaultSmsApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val repository = container.settings
    val settings by repository.settings.collectAsStateWithLifecycle()
    val otpSync by container.otpSync.state.collectAsStateWithLifecycle()
    var exactAlarms by remember { mutableStateOf(container.scheduler.canScheduleExact()) }
    var isDefault by remember { mutableStateOf(DefaultSmsApp.isDefault(context)) }
    LifecycleResumeEffect(Unit) {
        exactAlarms = container.scheduler.canScheduleExact()
        isDefault = DefaultSmsApp.isDefault(context)
        onPauseOrDispose { }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Notifications")
            SwitchRow(
                title = "Show notifications",
                summary = "Get notified when new messages arrive",
                checked = settings.notificationsEnabled,
                onCheckedChange = { value -> repository.update { it.copy(notificationsEnabled = value) } },
            )
            SwitchRow(
                title = "Show message content",
                summary = "Show sender and text in notifications",
                checked = settings.showPreview,
                enabled = settings.notificationsEnabled,
                onCheckedChange = { value -> repository.update { it.copy(showPreview = value) } },
            )
            SwitchRow(
                title = "Copy verification codes",
                summary = "Add a Copy button to notifications that contain a one-time code",
                checked = settings.copyCodeAction,
                enabled = settings.notificationsEnabled && settings.showPreview,
                onCheckedChange = { value -> repository.update { it.copy(copyCodeAction = value) } },
            )
            NavRow(
                title = "Sound, vibration and pop-ups",
                summary = "Managed in system notification settings",
                icon = Icons.Filled.Notifications,
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }

            SectionHeader("Connected devices")
            NavRow(
                title = "OTP sync",
                summary = when {
                    !otpSync.available -> "Not available in this build"
                    otpSync.account == null -> "Sign in to send verification codes to your computer"
                    otpSync.active -> "On for ${otpSync.account?.email ?: "your account"}"
                    else -> "Off"
                },
                icon = Icons.Filled.Devices,
            ) { onNavigate(Routes.OTP_SYNC) }

            SectionHeader("Sending")
            SwitchRow(
                title = "Delivery reports",
                summary = "Ask the network to confirm when each text is delivered",
                checked = settings.deliveryReports,
                onCheckedChange = { value -> repository.update { it.copy(deliveryReports = value) } },
            )
            NavRow("Quick responses", "Saved replies you can insert with one tap", Icons.Filled.Bolt) {
                onNavigate(Routes.QUICK_RESPONSES)
            }
            if (!exactAlarms) {
                NavRow(
                    title = "Send scheduled messages on time",
                    summary = "Allow exact alarms. Without this, Android may delay scheduled messages by a few minutes.",
                    icon = Icons.Filled.Alarm,
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    )
                }
            }

            SectionHeader("Privacy and storage")
            NavRow("Block numbers and spam", "Blocked numbers, phrases and messages", Icons.Filled.Block) {
                onNavigate(Routes.BLOCKED)
            }
            SwitchRow(
                title = "Keep deleted messages in Trash",
                summary = "Text messages can be restored for 30 days",
                checked = settings.trashEnabled,
                onCheckedChange = { value -> repository.update { it.copy(trashEnabled = value) } },
            )
            NavRow("Trash", null, Icons.Filled.Delete) { onNavigate(Routes.TRASH) }

            SectionHeader("About")
            NavRow(
                title = "Default SMS app",
                summary = if (isDefault) "Messages is your default SMS app" else "Messages isn't your default SMS app",
                icon = Icons.Filled.Sms,
            ) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            NavRow("Version", version, Icons.Filled.Info)
        }
    }
}
