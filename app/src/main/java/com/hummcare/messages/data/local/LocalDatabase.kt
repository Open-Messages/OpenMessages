package com.hummcare.messages.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// App-owned data that the system SMS provider has no place for.

@Entity(tableName = "pinned_threads")
data class PinnedThread(
    @PrimaryKey val threadId: Long,
    val pinnedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey val threadId: Long,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "starred_messages", indices = [Index("threadId")])
data class StarredMessage(
    @PrimaryKey val messageKey: String,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val incoming: Boolean,
    val starredAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "scheduled_messages", indices = [Index("threadId")])
data class ScheduledMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    /** Recipients joined with [ADDRESS_SEPARATOR]. */
    val addresses: String,
    val body: String,
    val sendAt: Long,
    val subscriptionId: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

const val ADDRESS_SEPARATOR = "\n"

fun ScheduledMessage.addressList(): List<String> =
    addresses.split(ADDRESS_SEPARATOR).filter { it.isNotBlank() }

@Entity(tableName = "trash")
data class TrashedMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,
    val read: Boolean,
    val subscriptionId: Int,
    val deletedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "quick_responses")
data class QuickResponse(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val position: Int = 0,
)

@Entity(tableName = "blocked_phrases")
data class BlockedPhrase(
    @PrimaryKey val phrase: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_messages")
data class BlockedMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val date: Long,
    val subscriptionId: Int,
    val reason: String,
)

@Dao
interface PinnedDao {
    @Query("SELECT threadId FROM pinned_threads ORDER BY pinnedAt DESC")
    fun observeIds(): Flow<List<Long>>

    @Upsert
    suspend fun pin(items: List<PinnedThread>)

    @Query("DELETE FROM pinned_threads WHERE threadId IN (:threadIds)")
    suspend fun unpin(threadIds: List<Long>)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts")
    fun observeAll(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE threadId = :threadId")
    suspend fun get(threadId: Long): Draft?

    @Upsert
    suspend fun upsert(draft: Draft)

    @Query("DELETE FROM drafts WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)
}

@Dao
interface StarredDao {
    @Query("SELECT * FROM starred_messages ORDER BY starredAt DESC")
    fun observeAll(): Flow<List<StarredMessage>>

    @Query("SELECT messageKey FROM starred_messages WHERE threadId = :threadId")
    fun observeKeys(threadId: Long): Flow<List<String>>

    @Upsert
    suspend fun upsert(message: StarredMessage)

    @Query("DELETE FROM starred_messages WHERE messageKey IN (:keys)")
    suspend fun delete(keys: List<String>)

    @Query("DELETE FROM starred_messages WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: Long)
}

@Dao
interface ScheduledDao {
    @Query("SELECT * FROM scheduled_messages ORDER BY sendAt")
    fun observeAll(): Flow<List<ScheduledMessage>>

    /** Newest first, matching the reversed message list in a conversation. */
    @Query("SELECT * FROM scheduled_messages WHERE threadId = :threadId ORDER BY sendAt DESC")
    fun observeForThread(threadId: Long): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages")
    suspend fun all(): List<ScheduledMessage>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun get(id: Long): ScheduledMessage?

    @Insert
    suspend fun insert(message: ScheduledMessage): Long

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC, date DESC")
    fun observeAll(): Flow<List<TrashedMessage>>

    @Query("SELECT * FROM trash")
    suspend fun all(): List<TrashedMessage>

    @Insert
    suspend fun insertAll(items: List<TrashedMessage>)

    @Query("DELETE FROM trash WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM trash")
    suspend fun clear()

    @Query("DELETE FROM trash WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface QuickResponseDao {
    @Query("SELECT * FROM quick_responses ORDER BY position, id")
    fun observeAll(): Flow<List<QuickResponse>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM quick_responses")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(response: QuickResponse)

    @Update
    suspend fun update(response: QuickResponse)

    @Delete
    suspend fun delete(response: QuickResponse)
}

@Dao
interface BlockingDao {
    @Query("SELECT * FROM blocked_phrases ORDER BY phrase COLLATE NOCASE")
    fun observePhrases(): Flow<List<BlockedPhrase>>

    @Query("SELECT * FROM blocked_phrases")
    suspend fun phrases(): List<BlockedPhrase>

    @Upsert
    suspend fun upsertPhrase(phrase: BlockedPhrase)

    @Query("DELETE FROM blocked_phrases WHERE phrase = :phrase")
    suspend fun deletePhrase(phrase: String)

    @Query("SELECT * FROM blocked_messages ORDER BY date DESC")
    fun observeBlockedMessages(): Flow<List<BlockedMessage>>

    @Insert
    suspend fun insertBlockedMessage(message: BlockedMessage)

    @Query("DELETE FROM blocked_messages WHERE id = :id")
    suspend fun deleteBlockedMessage(id: Long)
}

@Database(
    entities = [
        PinnedThread::class, Draft::class, StarredMessage::class, ScheduledMessage::class,
        TrashedMessage::class, QuickResponse::class, BlockedPhrase::class, BlockedMessage::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun pinnedDao(): PinnedDao
    abstract fun draftDao(): DraftDao
    abstract fun starredDao(): StarredDao
    abstract fun scheduledDao(): ScheduledDao
    abstract fun trashDao(): TrashDao
    abstract fun quickResponseDao(): QuickResponseDao
    abstract fun blockingDao(): BlockingDao

    companion object {
        private val DEFAULT_QUICK_RESPONSES = listOf(
            "Can't talk now. I'll call you back.",
            "On my way.",
            "Running a few minutes late.",
            "Sounds good!",
            "Thanks!",
            "Call me when you can.",
        )

        fun build(context: Context): LocalDatabase =
            Room.databaseBuilder(context, LocalDatabase::class.java, "messages-local.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        DEFAULT_QUICK_RESPONSES.forEachIndexed { index, text ->
                            db.execSQL(
                                "INSERT INTO quick_responses (text, position) VALUES (?, ?)",
                                arrayOf<Any>(text, index),
                            )
                        }
                    }
                })
                .build()
    }
}
