package com.hummcare.messages.util

/** Finds one-time verification codes in message bodies. */
object Otp {
    private val keyword = Regex(
        "(?i)\\b(code|otp|verification|verify|passcode|password|pin|one[- ]?time|2fa|security|login|sign[- ]?in)\\b"
    )
    private val code = Regex("(?<![\\d.,/:])\\d{4,8}(?!\\d|[.,/:]\\d)")

    fun find(text: String): String? {
        if (text.isBlank() || !keyword.containsMatchIn(text)) return null
        return code.find(text)?.value
    }
}
