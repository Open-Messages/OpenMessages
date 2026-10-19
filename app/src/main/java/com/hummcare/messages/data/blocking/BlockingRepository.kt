package com.hummcare.messages.data.blocking

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.Telephony
import com.hummcare.messages.data.local.BlockedMessage
import com.hummcare.messages.data.local.BlockedPhrase
import com.hummcare.messages.data.local.BlockingDao
import com.hummcare.messages.data.model.SmsRecord
import com.hummcare.messages.data.telephony.TelephonyRepository
import com.hummcare.messages.util.observe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

data class BlockedNumber(val id: Long, val number: String)

/**
 * Number blocking uses the platform [BlockedNumberContract] (shared with the dialer, and enforced
 * by the telephony stack before SMS reach us). Phrase blocking is app-level and applied on receipt.
 */
class BlockingRepository(
    private val context: Context,
    private val dao: BlockingDao,
    private val telephony: TelephonyRepository,
) {
    private val resolver = context.contentResolver

    fun canBlock(): Boolean =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun blockedNumbers(): Flow<List<BlockedNumber>> =
        resolver.observe(BlockedNumbers.CONTENT_URI).mapLatest { loadBlockedNumbers() }.flowOn(Dispatchers.IO)

    private fun loadBlockedNumbers(): List<BlockedNumber> = runCatching {
        resolver.query(
            BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
            null, null, BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
        )?.use { c ->
            buildList { while (c.moveToNext()) add(BlockedNumber(c.getLong(0), c.getString(1).orEmpty())) }
        }
    }.getOrNull().orEmpty()

    fun isBlocked(number: String): Boolean =
        runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)

    suspend fun block(number: String): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number.trim()) }
        runCatching { resolver.insert(BlockedNumbers.CONTENT_URI, values) }.getOrNull() != null
    }

    suspend fun unblock(number: String) = withContext(Dispatchers.IO) {
        runCatching { BlockedNumberContract.unblock(context, number) }
    }

    val phrases: Flow<List<BlockedPhrase>> = dao.observePhrases()

    suspend fun addPhrase(phrase: String) {
        if (phrase.isNotBlank()) dao.upsertPhrase(BlockedPhrase(phrase.trim()))
    }

    suspend fun removePhrase(phrase: String) = dao.deletePhrase(phrase)

    suspend fun matchingPhrase(body: String): String? =
        dao.phrases().firstOrNull { body.contains(it.phrase, ignoreCase = true) }?.phrase

    val blockedMessages: Flow<List<BlockedMessage>> = dao.observeBlockedMessages()

    suspend fun recordBlocked(address: String, body: String, subscriptionId: Int, reason: String) =
        dao.insertBlockedMessage(
            BlockedMessage(
                address = address,
                body = body,
                date = System.currentTimeMillis(),
                subscriptionId = subscriptionId,
                reason = reason,
            )
        )

    /** Moves a phrase-blocked message into the regular inbox. */
    suspend fun restore(message: BlockedMessage) = withContext(Dispatchers.IO) {
        telephony.restoreSms(
            SmsRecord(
                threadId = 0,
                address = message.address,
                body = message.body,
                date = message.date,
                dateSent = message.date,
                type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                read = true,
                subscriptionId = message.subscriptionId,
            )
        )
        dao.deleteBlockedMessage(message.id)
    }

    suspend fun deleteBlockedMessage(id: Long) = dao.deleteBlockedMessage(id)
}
