package com.hummcare.messages.data.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for synced codes. The format is documented in docs/otp-sync.md and must
 * stay byte-for-byte compatible with the desktop client:
 *
 * - key = PBKDF2-HMAC-SHA256(UTF-8(passphrase), salt, iterations, 32 bytes)
 * - each record = AES-256-GCM with a random 12-byte IV, 128-bit tag, and AAD binding it to the
 *   Firebase uid so ciphertext can't be replayed into another account.
 */
object OtpCrypto {
    const val VERSION = 1
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val ITERATIONS = 600_000
    const val CHECK_PLAINTEXT = "openmessages-otp-sync-check"

    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    class Sealed(val iv: String, val ct: String)

    fun otpAad(uid: String) = "openmessages/otp/v$VERSION/$uid".toByteArray()
    fun checkAad(uid: String) = "openmessages/check/v$VERSION/$uid".toByteArray()

    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    /**
     * PBKDF2 implemented over [Mac] rather than SecretKeyFactory: Android's providers have differed
     * in how they turn passphrase chars into bytes, and the desktop app needs exact UTF-8.
     */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        // 32-byte output = exactly one SHA-256 block, so only block index 1 is needed.
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result.copyOf(KEY_BYTES)
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return Sealed(iv = b64.encodeToString(iv), ct = b64.encodeToString(cipher.doFinal(plaintext)))
    }

    /** Throws [javax.crypto.AEADBadTagException] when the key is wrong. */
    fun decrypt(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, b64d.decode(sealed.iv)))
        cipher.updateAAD(aad)
        return cipher.doFinal(b64d.decode(sealed.ct))
    }

    fun encodeSalt(salt: ByteArray): String = b64.encodeToString(salt)
    fun decodeSalt(salt: String): ByteArray = b64d.decode(salt)

    /** A random key in the form XXXX-XXXX-XXXX-XXXX-XXXX (100 bits), easy to type on a computer. */
    fun generatePassphrase(): String {
        // Crockford base32: no I, L, O or U to avoid misreading.
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return (1..5).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
    }
}
