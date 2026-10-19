package com.hummcare.messages.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import androidx.core.net.toUri
import com.hummcare.messages.data.model.Recipient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ContactSuggestion(val name: String, val number: String, val label: String, val photoUri: Uri?)

class ContactsRepository(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Recipient>()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun invalidate() = cache.clear()

    /** Resolves an address to a contact. Blocking; call off the main thread. */
    fun lookup(address: String): Recipient {
        if (address.isBlank() || !hasPermission()) return Recipient(address)
        return cache.getOrPut(address) { queryRecipient(address) }
    }

    private fun queryRecipient(address: String): Recipient {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        val projection = arrayOf(
            PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                Recipient(
                    address = address,
                    name = c.getString(2),
                    photoUri = c.getString(3)?.toUri(),
                    contactUri = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)),
                )
            }
        }.getOrNull() ?: Recipient(address)
    }

    suspend fun search(query: String, limit: Int = 50): List<ContactSuggestion> = withContext(Dispatchers.IO) {
        if (!hasPermission() || query.isBlank()) return@withContext emptyList()
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI)
        val seen = HashSet<String>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                buildList {
                    while (size < limit && c.moveToNext()) {
                        val number = c.getString(1) ?: continue
                        if (!seen.add(PhoneNumberUtils.normalizeNumber(number))) continue
                        add(
                            ContactSuggestion(
                                name = c.getString(0) ?: number,
                                number = number,
                                label = Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString(),
                                photoUri = c.getString(4)?.toUri(),
                            )
                        )
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /** Reads the number from a row returned by the system phone picker. */
    suspend fun numberFromPickerResult(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
