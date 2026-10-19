package com.hummcare.messages.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Shared vector with the macOS app's OtpCryptoTests; PBKDF2 output was cross-checked against
 * Python's hashlib. If this breaks, the desktop app can no longer read synced codes.
 */
class OtpCryptoTest {
    private val passphrase = "Grüße ABCD-EFGH"
    private val salt = ByteArray(16) { it.toByte() }
    private val uid = "testUid123"
    private val plaintext = """{"code":"482913","sender":"+15551234567","body":"Your code is 482913","receivedAt":1760870400000}"""

    @Test
    fun deriveKeyMatchesVector() {
        val key = OtpCrypto.deriveKey(passphrase, salt, iterations = 1000)
        assertEquals("pdKKPF59vu2JWYdNAdDt9J6+uHEZHQyEa4pvrfBbQL8=", Base64.getEncoder().encodeToString(key))
    }

    @Test
    fun decryptsCiphertextFromDesktopVector() {
        val key = OtpCrypto.deriveKey(passphrase, salt, iterations = 1000)
        val sealed = OtpCrypto.Sealed(
            iv = "AQEBAQEBAQEBAQEB",
            ct = "o3huhmbAJCdPX2n+7F4sAro/efe0GH3xTvcmRFe+Mb+ZfFEEzUXIVPuJzv6z098YWX1LfVeh6s35chcAcD+6ypDuYCRMLEDjk5TxZGCWCfuPt2awGfQf3NlkCj7uU0uYUorH9fQdzXanbetv0yDueW8=",
        )
        assertEquals(plaintext, String(OtpCrypto.decrypt(key, sealed, OtpCrypto.otpAad(uid))))
    }

    @Test
    fun roundTripsAndChecksPassphraseFormat() {
        val key = OtpCrypto.deriveKey("x", salt, iterations = 1)
        val sealed = OtpCrypto.encrypt(key, plaintext.toByteArray(), OtpCrypto.checkAad(uid))
        assertEquals(plaintext, String(OtpCrypto.decrypt(key, sealed, OtpCrypto.checkAad(uid))))
        assertTrue(OtpCrypto.generatePassphrase().matches(Regex("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){4}")))
    }
}
