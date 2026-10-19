package com.hummcare.messages.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The sync key as held on this device, tied to the account it was set up for. */
class LocalSyncKey(val uid: String, val passphrase: String, val key: ByteArray)

/**
 * Stores the sync passphrase and derived key on this device only, encrypted with a non-exportable
 * Android Keystore key. The prefs file is excluded from backups (see res/xml), and if it's ever
 * restored somewhere the Keystore key doesn't exist, it's treated as no key.
 */
class SyncKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val key: StateFlow<LocalSyncKey?> = state.asStateFlow()
    val current: LocalSyncKey? get() = state.value

    @Synchronized
    fun save(value: LocalSyncKey) {
        prefs.edit {
            putString(KEY_UID, value.uid)
            putString(KEY_PASSPHRASE, wrap(value.passphrase.toByteArray(Charsets.UTF_8)))
            putString(KEY_DERIVED, wrap(value.key))
        }
        state.value = value
    }

    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        state.value = null
    }

    private fun read(): LocalSyncKey? = runCatching {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val passphrase = unwrap(prefs.getString(KEY_PASSPHRASE, null) ?: return null)
        val derived = unwrap(prefs.getString(KEY_DERIVED, null) ?: return null)
        LocalSyncKey(uid, String(passphrase, Charsets.UTF_8), derived)
    }.getOrElse {
        prefs.edit { clear() }
        null
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "otp_sync_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "otp_sync_wrapping_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_UID = "uid"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_DERIVED = "derived_key"
    }
}
