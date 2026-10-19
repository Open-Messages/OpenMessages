package com.hummcare.messages

import android.content.Context
import com.google.firebase.FirebaseApp
import com.hummcare.messages.data.account.AccountRepository
import com.hummcare.messages.data.blocking.BlockingRepository
import com.hummcare.messages.data.contacts.ContactsRepository
import com.hummcare.messages.data.local.LocalDatabase
import com.hummcare.messages.data.scheduled.MessageScheduler
import com.hummcare.messages.data.settings.SettingsRepository
import com.hummcare.messages.data.sim.SimRepository
import com.hummcare.messages.data.sync.OtpSyncRepository
import com.hummcare.messages.data.sync.SyncKeyStore
import com.hummcare.messages.data.telephony.TelephonyRepository
import com.hummcare.messages.data.trash.TrashManager
import com.hummcare.messages.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; one instance per process, owned by [MessagesApp]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Scope for work that must outlive a screen, e.g. sending or deleting after navigating away. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: LocalDatabase = LocalDatabase.build(appContext)
    val settings = SettingsRepository(appContext)
    val contacts = ContactsRepository(appContext)
    val sims = SimRepository(appContext)
    val telephony = TelephonyRepository(appContext, contacts)
    val blocking = BlockingRepository(appContext, db.blockingDao(), telephony)
    val smsSender = SmsSender(appContext, telephony, settings)
    val scheduler = MessageScheduler(appContext, db.scheduledDao(), smsSender)
    val trash = TrashManager(telephony, db, settings)

    /** Null when the build has no google-services.json; account and sync features then stay off. */
    private val firebaseApp: FirebaseApp? = FirebaseApp.getApps(appContext).firstOrNull() ?: FirebaseApp.initializeApp(appContext)
    val accounts = AccountRepository(appContext, firebaseApp)
    val otpSync = OtpSyncRepository(firebaseApp, accounts, SyncKeyStore(appContext), settings, applicationScope)
}
