package com.hummcare.messages

import android.app.Application
import android.content.Context
import com.hummcare.messages.notifications.NotificationHelper
import kotlinx.coroutines.launch

class MessagesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        container.applicationScope.launch {
            container.trash.purgeExpired()
            container.scheduler.restoreAlarms()
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MessagesApp).container
