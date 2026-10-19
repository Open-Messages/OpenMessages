package com.hummcare.messages.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hummcare.messages.appContainer
import com.hummcare.messages.data.sync.OtpCrypto
import com.hummcare.messages.data.sync.RemoteKeyInfo
import com.hummcare.messages.ui.components.BackButton
import com.hummcare.messages.ui.components.ConfirmDialog
import com.hummcare.messages.ui.components.EmptyState
import com.hummcare.messages.ui.components.NavRow
import com.hummcare.messages.ui.components.SectionHeader
import com.hummcare.messages.ui.components.SwitchRow
import com.hummcare.messages.util.copyToClipboard
import kotlinx.coroutines.launch

private const val MIN_PASSPHRASE = 12

/** What the account has on the server, looked up while this device has no key. */
private sealed interface RemoteLookup {
    data object Loading : RemoteLookup
    data object None : RemoteLookup
    class Exists(val info: RemoteKeyInfo) : RemoteLookup
    class Failed(val message: String) : RemoteLookup
}

private enum class KeyDialog { Create, Unlock, Show, ConfirmReset, ConfirmDeleteCodes, ConfirmForget }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val container = context.appContainer
    val sync = container.otpSync
    val state by sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }
    var remote by remember { mutableStateOf<RemoteLookup>(RemoteLookup.Loading) }
    var lookupAttempt by remember { mutableIntStateOf(0) }

    /** Runs [block] with a progress bar, reporting failures in a snackbar. */
    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Something went wrong")
            } finally {
                busy = false
            }
        }
    }

    val uid = state.account?.uid
    LaunchedEffect(uid, state.hasKey, lookupAttempt) {
        if (uid == null || state.hasKey) return@LaunchedEffect
        remote = RemoteLookup.Loading
        remote = try {
            sync.remoteKey()?.let(RemoteLookup::Exists) ?: RemoteLookup.None
        } catch (e: Exception) {
            RemoteLookup.Failed(e.message ?: "Couldn't reach the server")
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("OTP sync") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.available) {
            EmptyState(
                Icons.Filled.CloudOff,
                "OTP sync isn't available",
                "This build of Messages wasn't set up with a sync server.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Send verification codes you receive to Messages on your Windows or Mac computer. " +
                    "Codes are encrypted on this phone with a sync key that only you know, " +
                    "so nobody else, including the server, can read them. Signing in is only needed for this feature.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SectionHeader("Account")
            val account = state.account
            if (account == null) {
                Button(
                    onClick = {
                        run { if (activity != null) container.accounts.signInWithGoogle(activity) }
                    },
                    enabled = !busy && activity != null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Sign in with Google") }
                return@Column
            }
            NavRow(account.displayName ?: account.email ?: "Signed in", account.email, Icons.Filled.AccountCircle)
            NavRow("Sign out", "Stops syncing and removes the sync key from this phone", Icons.AutoMirrored.Filled.Logout) {
                run { container.accounts.signOut() }
            }

            SectionHeader("Sync key")
            if (state.hasKey) {
                SwitchRow(
                    title = "Sync verification codes",
                    summary = "Encrypt each new code and send it to your computer. Codes are kept for 24 hours.",
                    checked = state.enabled,
                    onCheckedChange = sync::setEnabled,
                )
                NavRow("Show sync key", "Enter it in Messages on your computer", Icons.Filled.Visibility) {
                    dialog = KeyDialog.Show
                }
                NavRow("Change sync key", "Your computer will need the new key", Icons.Filled.LockReset) {
                    dialog = KeyDialog.ConfirmReset
                }
                NavRow("Delete synced codes", "Remove every code stored on the server", Icons.Filled.DeleteSweep) {
                    dialog = KeyDialog.ConfirmDeleteCodes
                }
                NavRow("Remove key from this phone", "Syncing stops until you enter it again", Icons.Filled.KeyOff) {
                    dialog = KeyDialog.ConfirmForget
                }
            } else {
                when (val r = remote) {
                    RemoteLookup.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    RemoteLookup.None -> NavRow(
                        "Create sync key",
                        "You'll enter the same key on your computer to read your codes",
                        Icons.Filled.Key,
                    ) { dialog = KeyDialog.Create }
                    is RemoteLookup.Exists -> {
                        NavRow("Enter your sync key", "This account already has a sync key", Icons.Filled.Key) {
                            dialog = KeyDialog.Unlock
                        }
                        NavRow("Forgot your key?", "Create a new one. Codes already synced will be deleted.", Icons.Filled.LockReset) {
                            dialog = KeyDialog.ConfirmReset
                        }
                    }
                    is RemoteLookup.Failed -> NavRow("Couldn't check your account", r.message, Icons.Filled.Refresh) {
                        lookupAttempt++
                    }
                }
            }
        }
    }

    when (dialog) {
        KeyDialog.Create -> CreateKeyDialog(
            onConfirm = { passphrase ->
                dialog = null
                run {
                    sync.createKey(passphrase)
                    snackbar.showSnackbar("Sync key saved")
                }
            },
            onDismiss = { dialog = null },
        )
        KeyDialog.Unlock -> (remote as? RemoteLookup.Exists)?.let { r ->
            UnlockDialog(
                onConfirm = { passphrase ->
                    dialog = null
                    run { if (!sync.unlock(passphrase, r.info)) snackbar.showSnackbar("That key doesn't match") }
                },
                onDismiss = { dialog = null },
            )
        }
        KeyDialog.Show -> {
            val passphrase = remember { sync.currentPassphrase() }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Your sync key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(passphrase.orEmpty(), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                        Text("Don't share it. Anyone with this key and access to your account can read your codes.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { context.copyToClipboard("Sync key", passphrase.orEmpty(), sensitive = true) }) {
                        Text("Copy")
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
            )
        }
        KeyDialog.ConfirmReset -> ConfirmDialog(
            title = "Create a new sync key?",
            message = "Codes already synced will be deleted, and your computer will need the new key.",
            confirmLabel = "Continue",
            onConfirm = { dialog = KeyDialog.Create },
            onDismiss = { if (dialog == KeyDialog.ConfirmReset) dialog = null },
        )
        KeyDialog.ConfirmDeleteCodes -> ConfirmDialog(
            title = "Delete synced codes?",
            message = "Every code stored on the server will be removed. New codes will keep syncing.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { run { sync.deleteSyncedCodes(); snackbar.showSnackbar("Synced codes deleted") } },
            onDismiss = { dialog = null },
        )
        KeyDialog.ConfirmForget -> ConfirmDialog(
            title = "Remove sync key?",
            message = "Syncing stops on this phone. Codes already synced stay readable on your computer.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = sync::forgetLocalKey,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun CreateKeyDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(OtpCrypto.generatePassphrase()) }
    val valid = value.trim().length >= MIN_PASSPHRASE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create sync key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Write this key down. You'll need it to read your codes on your computer, " +
                        "and it can't be recovered if you lose it."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Sync key") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { if (!valid) Text("Use at least $MIN_PASSPHRASE characters") },
                    isError = !valid,
                    trailingIcon = {
                        IconButton(onClick = { value = OtpCrypto.generatePassphrase() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Generate another key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = valid) { Text("Save key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnlockDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter sync key") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Sync key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
