package com.hummcare.messages.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hummcare.messages.appContainer
import com.hummcare.messages.ui.theme.MessagesTheme
import com.hummcare.messages.util.SmsIntents
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val navRequests = MutableStateFlow<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) navRequests.value = parse(intent)
        setContent {
            MessagesTheme {
                AppRoot(navRequests = navRequests, onNavRequestHandled = { navRequests.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navRequests.value = parse(intent)
    }

    override fun onStart() {
        super.onStart()
        // Picks up contact edits and permission changes made while we were in the background.
        appContainer.telephony.refresh()
    }

    private fun parse(intent: Intent?): NavRequest? = when (intent?.action) {
        ACTION_OPEN_THREAD ->
            intent.getLongExtra(EXTRA_THREAD_ID, -1).takeIf { it > 0 }?.let(NavRequest::OpenThread)

        Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
            val to = SmsIntents.recipients(intent.data)
            val body = SmsIntents.body(intent)
            if (to.isEmpty() && body == null) null else NavRequest.Compose(to, body)
        }

        Intent.ACTION_SEND ->
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { NavRequest.Compose(emptyList(), it) }

        else -> null
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.hummcare.messages.OPEN_THREAD"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}
