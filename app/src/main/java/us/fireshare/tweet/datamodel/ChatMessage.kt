package us.fireshare.tweet.datamodel

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.reflect.Type

// Custom deserializer to handle both Long and Double timestamp values
class ChatMessageDeserializer : JsonDeserializer<ChatMessage> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): ChatMessage {
        val jsonObject = json?.asJsonObject ?: throw JsonParseException("Invalid JSON")
        
        val receiptId = jsonObject.get("receiptId")?.asString ?: ""
        val authorId = jsonObject.get("authorId")?.asString ?: ""
        val content = jsonObject.get("content")?.asString ?: ""
        
        // Handle timestamp - convert Double to Long if needed
        val timestampElement = jsonObject.get("timestamp")
        val timestamp = when {
            timestampElement?.isJsonPrimitive == true -> {
                val primitive = timestampElement.asJsonPrimitive
                when {
                    primitive.isNumber -> {
                        val number = primitive.asNumber
                        when (number) {
                            is Long -> number
                            is Double -> number.toLong()
                            is Int -> number.toLong()
                            else -> number.toLong()
                        }
                    }
                    else -> throw JsonParseException("Timestamp must be a number")
                }
            }
            else -> throw JsonParseException("Timestamp is required")
        }
        
        // Handle sessionId (optional)
        val sessionId = jsonObject.get("sessionId")?.asLong
        
        // Handle attachments (optional)
        val attachmentsElement = jsonObject.get("attachments")
        val attachments = if (attachmentsElement?.isJsonArray == true) {
            val gson = Gson()
            gson.fromJson(attachmentsElement, Array<MimeiFileType>::class.java).toList()
        } else null
        
        return ChatMessage(
            receiptId = receiptId,
            authorId = authorId,
            content = content,
            attachments = attachments,
            timestamp = timestamp,
            sessionId = sessionId
        )
    }
}

@Serializable
data class ChatMessage(
    val receiptId: MimeiId,     // receiver of the message
    val authorId: MimeiId,      // author of the message
    val content: String,
    val attachments: List<MimeiFileType>? = null,  // media file
    val timestamp: Long,
    val sessionId: Long? = null  // reference to the chat session
)

@Serializable
data class ChatSession(
    val id: Long = 0,  // auto-generated session ID
    var timestamp: Long = System.currentTimeMillis(),    // last time the chat screen is opened
    val userId: MimeiId,    // always the appUser
    val receiptId: MimeiId, // whom the app user is chatting with
    var hasNews: Boolean,   // new message hasn't been read.
    var lastMessage: ChatMessage    // the most recent msg, could be from either appUser or the other party
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: String,
    val authorId: String,
    val content: String,
    val attachments: List<MimeiFileType>? = null,
    val timestamp: Long,
    val sessionId: Long? = null
)

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val userId: String,
    val receiptId: String,
    val hasNews: Boolean,
    val lastMessageId: Long
)

fun ChatMessage.toEntity(): ChatMessageEntity {
    return ChatMessageEntity(
        receiptId = this.receiptId,
        authorId = this.authorId,
        content = this.content,
        attachments = this.attachments,
        timestamp = this.timestamp,
        sessionId = this.sessionId
    )
}

fun ChatMessageEntity.toChatMessage(): ChatMessage {
    return ChatMessage(
        receiptId = this.receiptId,
        authorId = this.authorId,
        content = this.content,
        attachments = this.attachments,
        timestamp = this.timestamp,
        sessionId = this.sessionId
    )
}

fun ChatSession.toEntity(lastMessageId: Long): ChatSessionEntity {
    return ChatSessionEntity(
        id = this.id,
        timestamp = this.timestamp,
        userId = this.userId,
        receiptId = this.receiptId,
        hasNews = this.hasNews,
        lastMessageId = lastMessageId
    )
}

fun ChatSessionEntity.toChatSession(lastMessage: ChatMessage): ChatSession {
    return ChatSession(
        id = this.id,
        timestamp = this.timestamp,
        userId = this.userId,
        receiptId = this.receiptId,
        hasNews = this.hasNews,
        lastMessage = lastMessage
    )
}

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("""
        SELECT * FROM chat_messages 
        WHERE (authorId = :userId AND receiptId = :receiptId) 
           OR (authorId = :receiptId AND receiptId = :userId) 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    // message is bidirectional. The sender and receiver switches accordingly. Here we need to find
    // all conversations between them.
    suspend fun loadMessages(userId: String, receiptId: String, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): ChatMessageEntity?

    @Query("""
        SELECT * FROM chat_messages 
        WHERE (authorId = :userId AND receiptId = :receiptId) 
           OR (authorId = :receiptId AND receiptId = :userId) 
        ORDER BY timestamp DESC 
        LIMIT 1
    """)
    // the latest message between app user and receipt
    suspend fun getLatestMessage(userId: String, receiptId: String): ChatMessageEntity?
}

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE userId = :userId AND receiptId = :receiptId LIMIT 1")
    suspend fun getSession(userId: String, receiptId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE userId = :userId ORDER BY timestamp DESC")
    // get all chat sessions of the current user
    suspend fun getAllSessions(userId: String): List<ChatSessionEntity>

    @Query("UPDATE chat_sessions SET timestamp = :timestamp, lastMessageId = :lastMessageId, hasNews = :hasNews WHERE userId = :userId AND receiptId = :receiptId")
    suspend fun updateSession(userId: String, receiptId: String, timestamp: Long, lastMessageId: Long, hasNews: Boolean)
}

@Database(entities = [ChatMessageEntity::class, ChatSessionEntity::class], version = 5)
@TypeConverters(MimeiFileTypeListConverter::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    // Add sessionId column to chat_messages table
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN sessionId INTEGER")
                    
                    // The attachments column type change from List<MimeiId> to List<MimeiFileType> 
                    // will be handled by the new TypeConverter automatically
                    // No need to modify the column as Room handles the conversion
                    
                    // The id column in chat_sessions is already auto-generated, so no migration needed
                } catch (e: Exception) {
                    // If the column already exists, ignore the error
                    if (e.message?.contains("duplicate column name") != true) {
                        throw e
                    }
                }
            }
        }

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                                context.applicationContext,
                                ChatDatabase::class.java,
                                "chat_database"
                            )
                            .fallbackToDestructiveMigration(false) // Re-enable proper migration
                            .addMigrations(MIGRATION_4_5)
                            .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MimeiFileTypeListConverter {
    @TypeConverter
    fun fromList(list: List<MimeiFileType>?): String? {
        return list?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toList(data: String?): List<MimeiFileType>? {
        return data?.let { Json.decodeFromString<List<MimeiFileType>>(it) }
    }
}