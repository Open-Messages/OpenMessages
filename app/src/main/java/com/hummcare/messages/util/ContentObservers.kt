package com.hummcare.messages.util

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Emits once immediately, then whenever any of [uris] (or their descendants) change. */
fun ContentResolver.observe(vararg uris: Uri): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    uris.forEach { uri -> runCatching { registerContentObserver(uri, true, observer) } }
    trySend(Unit)
    awaitClose { unregisterContentObserver(observer) }
}.conflate()
