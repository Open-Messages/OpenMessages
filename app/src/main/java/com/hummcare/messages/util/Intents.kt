package com.hummcare.messages.util

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

object SmsIntents {
    /** Parses recipients from `sms:`, `smsto:`, `mms:` and `mmsto:` URIs (comma or semicolon separated). */
    fun recipients(uri: Uri?): List<String> {
        val ssp = uri?.schemeSpecificPart ?: return emptyList()
        return ssp.substringBefore('?')
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun body(intent: Intent): String? {
        intent.getStringExtra("sms_body")?.let { return it }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { return it }
        val query = intent.data?.schemeSpecificPart?.substringAfter('?', "") ?: return null
        return query.split('&')
            .firstOrNull { it.startsWith("body=") }
            ?.removePrefix("body=")
            ?.let(Uri::decode)
    }
}

object DefaultSmsApp {
    fun isDefault(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_SMS) && roles.isRoleHeld(RoleManager.ROLE_SMS)
    }

    fun requestIntent(context: Context): Intent? =
        context.getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_SMS) }
            ?.createRequestRoleIntent(RoleManager.ROLE_SMS)
}

fun Context.copyToClipboard(label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Keeps the text out of the clipboard preview.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
