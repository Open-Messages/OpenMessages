package com.hummcare.messages.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val showPreview: Boolean = true,
    val copyCodeAction: Boolean = true,
    val deliveryReports: Boolean = false,
    val trashEnabled: Boolean = true,
    val otpSyncEnabled: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        prefs.edit {
            putBoolean(KEY_NOTIFICATIONS, next.notificationsEnabled)
            putBoolean(KEY_PREVIEW, next.showPreview)
            putBoolean(KEY_COPY_CODE, next.copyCodeAction)
            putBoolean(KEY_DELIVERY_REPORTS, next.deliveryReports)
            putBoolean(KEY_TRASH, next.trashEnabled)
            putBoolean(KEY_OTP_SYNC, next.otpSyncEnabled)
        }
        state.value = next
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, defaults.notificationsEnabled),
            showPreview = prefs.getBoolean(KEY_PREVIEW, defaults.showPreview),
            copyCodeAction = prefs.getBoolean(KEY_COPY_CODE, defaults.copyCodeAction),
            deliveryReports = prefs.getBoolean(KEY_DELIVERY_REPORTS, defaults.deliveryReports),
            trashEnabled = prefs.getBoolean(KEY_TRASH, defaults.trashEnabled),
            otpSyncEnabled = prefs.getBoolean(KEY_OTP_SYNC, defaults.otpSyncEnabled),
        )
    }

    private companion object {
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_PREVIEW = "show_preview"
        const val KEY_COPY_CODE = "copy_code_action"
        const val KEY_DELIVERY_REPORTS = "delivery_reports"
        const val KEY_TRASH = "trash_enabled"
        const val KEY_OTP_SYNC = "otp_sync_enabled"
    }
}
