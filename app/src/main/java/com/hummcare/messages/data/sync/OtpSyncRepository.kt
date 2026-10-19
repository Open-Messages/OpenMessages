package com.hummcare.messages.data.sync

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.hummcare.messages.data.account.Account
import com.hummcare.messages.data.account.AccountRepository
import com.hummcare.messages.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

data class OtpSyncState(
    /** False when this build has no Firebase configuration. */
    val available: Boolean,
    val account: Account?,
    val hasKey: Boolean,
    val enabled: Boolean,
) {
    val active: Boolean get() = available && account != null && hasKey && enabled
}

/** Key parameters published to `users/{uid}` so other devices can derive the same key. */
class RemoteKeyInfo(val salt: ByteArray, val iterations: Int, val check: OtpCrypto.Sealed)

/**
 * Encrypts verification codes on this device and stores only ciphertext in Firestore under
 * `users/{uid}/otps`. The passphrase never leaves the device; see docs/otp-sync.md.
 */
class OtpSyncRepository(
    firebaseApp: FirebaseApp?,
    private val accounts: AccountRepository,
    private val keys: SyncKeyStore,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private val firestore: FirebaseFirestore? = firebaseApp?.let(FirebaseFirestore::getInstance)

    val state: StateFlow<OtpSyncState> = combine(accounts.account, keys.key, settings.settings) { account, key, s ->
        OtpSyncState(
            available = firestore != null && accounts.isAvailable,
            account = account,
            hasKey = key != null && key.uid == account?.uid,
            enabled = s.otpSyncEnabled,
        )
    }.stateIn(scope, SharingStarted.Eagerly, OtpSyncState(false, null, false, false))

    init {
        // The local key belongs to one account: drop it and stop syncing on sign-out or account switch.
        scope.launch {
            accounts.account.collect { account ->
                val key = keys.current
                if (key != null && key.uid != account?.uid) {
                    keys.clear()
                    settings.update { it.copy(otpSyncEnabled = false) }
                }
            }
        }
    }

    /** The key set up on another device (or a previous install), or null if this account has none. */
    suspend fun remoteKey(): RemoteKeyInfo? {
        val (db, uid) = requireSignedIn()
        val snapshot = userDoc(db, uid).get().await()
        @Suppress("UNCHECKED_CAST")
        val meta = snapshot.get(FIELD_META) as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val check = meta["check"] as? Map<String, Any?> ?: return null
        return RemoteKeyInfo(
            salt = OtpCrypto.decodeSalt(meta["salt"] as String),
            iterations = (meta["iterations"] as Number).toInt(),
            check = OtpCrypto.Sealed(iv = check["iv"] as String, ct = check["ct"] as String),
        )
    }

    /**
     * Sets [passphrase] as the account's sync key, replacing any existing one. Codes already
     * synced were encrypted with the old key and can't be read anymore, so they are deleted.
     */
    suspend fun createKey(passphrase: String) {
        val (db, uid) = requireSignedIn()
        val salt = OtpCrypto.newSalt()
        val key = withContext(Dispatchers.Default) { OtpCrypto.deriveKey(passphrase, salt) }
        val check = OtpCrypto.encrypt(key, OtpCrypto.CHECK_PLAINTEXT.toByteArray(), OtpCrypto.checkAad(uid))
        deleteSyncedCodes()
        val meta = mapOf(
            "v" to OtpCrypto.VERSION,
            "kdf" to OtpCrypto.KDF,
            "iterations" to OtpCrypto.ITERATIONS,
            "salt" to OtpCrypto.encodeSalt(salt),
            "check" to mapOf("iv" to check.iv, "ct" to check.ct),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        userDoc(db, uid).set(mapOf(FIELD_META to meta), SetOptions.merge()).await()
        keys.save(LocalSyncKey(uid, passphrase, key))
    }

    /** Checks [passphrase] against the account's existing key. Returns false if it's wrong. */
    suspend fun unlock(passphrase: String, remote: RemoteKeyInfo): Boolean {
        val uid = requireSignedIn().second
        val key = withContext(Dispatchers.Default) { OtpCrypto.deriveKey(passphrase, remote.salt, remote.iterations) }
        val plain = try {
            OtpCrypto.decrypt(key, remote.check, OtpCrypto.checkAad(uid))
        } catch (_: AEADBadTagException) {
            return false
        }
        if (String(plain) != OtpCrypto.CHECK_PLAINTEXT) return false
        keys.save(LocalSyncKey(uid, passphrase, key))
        return true
    }

    fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(otpSyncEnabled = enabled && state.value.let { s -> s.account != null && s.hasKey }) }
    }

    fun currentPassphrase(): String? = keys.current?.passphrase

    /** Removes the key from this device only. Syncing stops until it's entered again. */
    fun forgetLocalKey() {
        keys.clear()
        settings.update { it.copy(otpSyncEnabled = false) }
    }

    suspend fun deleteSyncedCodes() {
        val (db, uid) = requireSignedIn()
        val collection = userDoc(db, uid).collection(COLLECTION_OTPS)
        while (true) {
            val page = collection.limit(400).get().await()
            if (page.isEmpty) break
            db.batch().apply { page.documents.forEach { delete(it.reference) } }.commit().await()
        }
    }

    /**
     * Encrypts and queues one code for upload. Doesn't wait for the server: Firestore persists the
     * write locally and sends it when the device is online, even after a process restart.
     */
    fun uploadIfEnabled(code: String, sender: String, body: String, receivedAt: Long) {
        val db = firestore ?: return
        val account = accounts.current ?: return
        val key = keys.current?.takeIf { it.uid == account.uid } ?: return
        if (!settings.current.otpSyncEnabled) return

        val payload = JSONObject()
            .put("code", code)
            .put("sender", sender)
            .put("body", body)
            .put("receivedAt", receivedAt)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val sealed = OtpCrypto.encrypt(key.key, payload, OtpCrypto.otpAad(account.uid))
        val record = mapOf(
            "v" to OtpCrypto.VERSION,
            "iv" to sealed.iv,
            "ct" to sealed.ct,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(receivedAt + TimeUnit.HOURS.toMillis(RETENTION_HOURS))),
        )
        userDoc(db, account.uid).collection(COLLECTION_OTPS).add(record)
            .addOnFailureListener { Log.w(TAG, "OTP upload failed", it) }
    }

    private fun requireSignedIn(): Pair<FirebaseFirestore, String> {
        val db = firestore ?: error("Firebase isn't configured in this build")
        val uid = accounts.current?.uid ?: error("Not signed in")
        return db to uid
    }

    private fun userDoc(db: FirebaseFirestore, uid: String) = db.collection(COLLECTION_USERS).document(uid)

    private companion object {
        const val TAG = "OtpSync"
        const val COLLECTION_USERS = "users"
        const val COLLECTION_OTPS = "otps"
        const val FIELD_META = "otpSync"
        const val RETENTION_HOURS = 24L
    }
}
