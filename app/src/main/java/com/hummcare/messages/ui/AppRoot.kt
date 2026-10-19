package com.hummcare.messages.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hummcare.messages.appContainer
import com.hummcare.messages.ui.conversations.ConversationListScreen
import com.hummcare.messages.ui.newmessage.NewMessageScreen
import com.hummcare.messages.ui.onboarding.SetDefaultScreen
import com.hummcare.messages.ui.scheduled.ScheduledScreen
import com.hummcare.messages.ui.search.SearchScreen
import com.hummcare.messages.ui.settings.BlockedScreen
import com.hummcare.messages.ui.settings.OtpSyncScreen
import com.hummcare.messages.ui.settings.QuickResponsesScreen
import com.hummcare.messages.ui.settings.SettingsScreen
import com.hummcare.messages.ui.starred.StarredScreen
import com.hummcare.messages.ui.thread.ThreadScreen
import com.hummcare.messages.ui.trash.TrashScreen
import com.hummcare.messages.util.DefaultSmsApp
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val CONVERSATIONS = "conversations"
    const val THREAD = "thread/{threadId}"
    const val NEW = "new?to={to}&body={body}"
    const val SEARCH = "search"
    const val STARRED = "starred"
    const val SCHEDULED = "scheduled"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val BLOCKED = "settings/blocked"
    const val QUICK_RESPONSES = "settings/quick-responses"
    const val OTP_SYNC = "settings/otp-sync"

    fun thread(threadId: Long) = "thread/$threadId"

    fun new(to: List<String> = emptyList(), body: String? = null): String {
        val params = buildList {
            if (to.isNotEmpty()) add("to=" + Uri.encode(to.joinToString(",")))
            if (!body.isNullOrEmpty()) add("body=" + Uri.encode(body))
        }
        return if (params.isEmpty()) "new" else "new?" + params.joinToString("&")
    }
}

/** Navigation requested from outside the UI: notifications, share sheet, sms: links. */
sealed interface NavRequest {
    data class OpenThread(val threadId: Long) : NavRequest
    data class Compose(val to: List<String>, val body: String?) : NavRequest
}

@Composable
fun AppRoot(navRequests: StateFlow<NavRequest?>, onNavRequestHandled: () -> Unit) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultSmsApp.isDefault(context)) }
    LifecycleResumeEffect(Unit) {
        isDefault = DefaultSmsApp.isDefault(context)
        onPauseOrDispose { }
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = DefaultSmsApp.isDefault(context)
        context.appContainer.telephony.refresh()
    }

    if (!isDefault) {
        SetDefaultScreen(onSetDefault = { DefaultSmsApp.requestIntent(context)?.let(roleLauncher::launch) })
        return
    }

    RequestRuntimePermissions()

    val nav = rememberNavController()
    val request by navRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val r = request) {
            is NavRequest.OpenThread -> nav.navigate(Routes.thread(r.threadId)) { launchSingleTop = true }
            is NavRequest.Compose -> nav.navigate(Routes.new(r.to, r.body))
            null -> return@LaunchedEffect
        }
        onNavRequestHandled()
    }

    NavHost(navController = nav, startDestination = Routes.CONVERSATIONS) {
        composable(Routes.CONVERSATIONS) {
            ConversationListScreen(
                onOpenThread = { nav.navigate(Routes.thread(it)) },
                onNewMessage = { nav.navigate(Routes.new()) },
                onNavigate = { nav.navigate(it) },
            )
        }
        composable(
            Routes.THREAD,
            arguments = listOf(navArgument("threadId") { type = NavType.LongType }),
        ) { entry ->
            ThreadScreen(
                threadId = entry.arguments?.getLong("threadId") ?: return@composable,
                onBack = { nav.popBackStack() },
                onForward = { body -> nav.navigate(Routes.new(body = body)) },
            )
        }
        composable(
            Routes.NEW,
            arguments = listOf(
                navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("body") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            NewMessageScreen(
                initialTo = entry.arguments?.getString("to")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                initialBody = entry.arguments?.getString("body").orEmpty(),
                onBack = { nav.popBackStack() },
                onSent = { threadId ->
                    nav.navigate(Routes.thread(threadId)) { popUpTo(Routes.NEW) { inclusive = true } }
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onBack = { nav.popBackStack() }, onOpenThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.STARRED) {
            StarredScreen(onBack = { nav.popBackStack() }, onOpenThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.SCHEDULED) {
            ScheduledScreen(onBack = { nav.popBackStack() }, onOpenThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) })
        }
        composable(Routes.BLOCKED) {
            BlockedScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.QUICK_RESPONSES) {
            QuickResponsesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.OTP_SYNC) {
            OtpSyncScreen(onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun RequestRuntimePermissions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        context.appContainer.telephony.refresh()
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}
