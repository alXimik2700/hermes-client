package com.hermes.messenger.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "messages")
data class HermesMessageEntity(
    @PrimaryKey
    val id: String,               // UUID generated on client
    val text: String,
    val timestamp: Long,          // Unix millis
    val isFromAgent: Boolean,     // true = Hermes reply, false = user
    val status: Int = 0,          // 0 = PENDING, 1 = SENT
    val messageType: String = "TEXT",  // TEXT, VOICE, IMAGE, FILE
    val localFilePath: String? = null, // local cache path (e.g. voice .m4a, photo from gallery)
    val fileUrl: String? = null,       // server URL filled after successful sync (e.g. /media/uuid.jpg)
    val serverId: Long? = null,        // server-assigned sequential id for correct ordering
    val target: String = "hermes"      // hermes or mimo
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val TYPE_TEXT = "TEXT"
        const val TYPE_VOICE = "VOICE"
        const val TYPE_IMAGE = "IMAGE"
        const val TYPE_FILE = "FILE"
    }
}

@Dao
interface MessageDao {
    /** Reactive stream for UI — all messages ordered by server sequence. */
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessagesFlow(): kotlinx.coroutines.flow.Flow<List<HermesMessageEntity>>

    /** Insert or ignore (idempotent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(msg: HermesMessageEntity)

    /** Check if message with same text+sender already exists (dedup). */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE text = :text AND isFromAgent = :isFromAgent)")
    suspend fun existsByTextAndSender(text: String, isFromAgent: Boolean): Boolean

    /** Update delivery status. */
    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: Int)

    /** Mark sent and persist server fileUrl. */
    @Query("UPDATE messages SET status = :status, fileUrl = :fileUrl WHERE id = :id")
    suspend fun markSent(id: String, status: Int, fileUrl: String?)

    /** Get all pending messages for sync. */
    @Query("SELECT * FROM messages WHERE status = 0 AND isFromAgent = 0 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<HermesMessageEntity>

    /** Count pending. */
    @Query("SELECT COUNT(*) FROM messages WHERE status = 0 AND isFromAgent = 0")
    suspend fun pendingCount(): Int

    /** Update timestamp to server value after send. Also set serverId. */
    @Query("UPDATE messages SET timestamp = :serverTimestamp, status = 1, serverId = :serverId WHERE id = :id")
    suspend fun markSentWithTime(id: String, serverTimestamp: Long, serverId: Long)

    /** Set serverId for media messages after upload. */
    @Query("UPDATE messages SET serverId = :serverId, status = 1 WHERE id = :id")
    suspend fun markSentWithServerId(id: String, serverId: Long)

    /** Dedup AI messages by server id. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE serverId = :serverId)")
    suspend fun existsByServerId(serverId: Long): Boolean
}

/** Migration v1→v2: add media columns. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'")
        db.execSQL("ALTER TABLE messages ADD COLUMN localFilePath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN fileUrl TEXT")
    }
}

/** Migration v2→v3: add serverId column for correct message ordering. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN serverId INTEGER")
    }
}

/** Migration v3→v4: add target column for mimo/hermes routing. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN target TEXT NOT NULL DEFAULT 'hermes'")
    }
}

@Database(entities = [HermesMessageEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "hermes_messages.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                 .build().also { INSTANCE = it }
            }
        }
    }
}
