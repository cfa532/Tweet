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
import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.reflect.Type
import java.util.UUID

// Custom deserializer to handle both Long and Double timestamp values
class ChatMessageDeserializer : JsonDeserializer<ChatMessage> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): ChatMessage {
        val jsonObject = json?.asJsonObject ?: throw JsonParseException("Invalid JSON")
        
        val id = jsonObject.get("id")?.asString
        val receiptId = jsonObject.get("receiptId")?.asString ?: ""
        val authorId = jsonObject.get("authorId")?.asString ?: ""
        val content = jsonObject.get("content")?.asString
        
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
        val sessionId = jsonObject.get("sessionId")?.asString
        
        // Handle attachments (optional)
        val attachmentsElement = jsonObject.get("attachments")
        val attachments = if (attachmentsElement?.isJsonArray == true) {
            val gson = Gson()
            gson.fromJson(attachmentsElement, Array<MimeiFileType>::class.java).toList()
        } else null
        
        // Handle success and errorMsg (optional)
        val success = jsonObject.get("success")?.asBoolean ?: true
        val errorMsg = jsonObject.get("errorMsg")?.asString
        
        return ChatMessage(
            id = id ?: ChatMessage.generateUniqueId(),
            receiptId = receiptId,
            authorId = authorId,
            content = content,
            attachments = attachments,
            timestamp = timestamp,
            sessionId = sessionId,
            success = success,
            errorMsg = errorMsg
        )
    }
}

@Serializable
data class ChatMessage(
    val id: String = generateUniqueId(),     // unique message ID - always generated
    val receiptId: MimeiId,     // receiver of the message
    val authorId: MimeiId,      // author of the message
    val content: String? = null,  // optional content
    val attachments: List<MimeiFileType>? = null,  // optional media files
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null,  // reference to the chat session (created locally)
    val success: Boolean = true,  // whether the message was sent successfully
    val errorMsg: String? = null  // error message if sending failed
) {
    init {
        // Validate that either content or attachments must be present
        require(content?.isNotBlank() == true || !attachments.isNullOrEmpty()) {
            "ChatMessage must have either non-empty content or attachments"
        }
    }
    
    companion object {
        /**
         * Generate a unique message ID using UUID
         */
        fun generateUniqueId(): String {
            return UUID.randomUUID().toString()
        }
        
        /**
         * Create a ChatMessage with content only
         */
        fun createTextMessage(
            receiptId: MimeiId,
            authorId: MimeiId,
            content: String,
            sessionId: String? = null
        ): ChatMessage {
            require(content.isNotBlank()) { "Content cannot be empty for text message" }
            return ChatMessage(
                receiptId = receiptId,
                authorId = authorId,
                content = content,
                sessionId = sessionId
            )
        }
        
        /**
         * Create a ChatMessage with attachments only
         */
        fun createMediaMessage(
            receiptId: MimeiId,
            authorId: MimeiId,
            attachments: List<MimeiFileType>,
            sessionId: String? = null
        ): ChatMessage {
            require(attachments.isNotEmpty()) { "Attachments cannot be empty for media message" }
            return ChatMessage(
                receiptId = receiptId,
                authorId = authorId,
                attachments = attachments,
                sessionId = sessionId
            )
        }
        
        /**
         * Create a ChatMessage with both content and attachments
         */
        fun createMixedMessage(
            receiptId: MimeiId,
            authorId: MimeiId,
            content: String,
            attachments: List<MimeiFileType>,
            sessionId: String? = null
        ): ChatMessage {
            require(content.isNotBlank()) { "Content cannot be empty for mixed message" }
            require(attachments.isNotEmpty()) { "Attachments cannot be empty for mixed message" }
            return ChatMessage(
                receiptId = receiptId,
                authorId = authorId,
                content = content,
                attachments = attachments,
                sessionId = sessionId
            )
        }
    }
    
    /**
     * Check if this message has content
     */
    fun hasContent(): Boolean = content?.isNotBlank() == true
    
    /**
     * Check if this message has attachments
     */
    fun hasAttachments(): Boolean = !attachments.isNullOrEmpty()
    
    /**
     * Check if this is a text-only message
     */
    fun isTextOnly(): Boolean = hasContent() && !hasAttachments()
    
    /**
     * Check if this is a media-only message
     */
    fun isMediaOnly(): Boolean = !hasContent() && hasAttachments()
    
    /**
     * Check if this is a mixed message (both content and attachments)
     */
    fun isMixedMessage(): Boolean = hasContent() && hasAttachments()
    
    /**
     * Create a copy of this message with the given sessionId
     */
    fun withSessionId(sessionId: String): ChatMessage {
        return this.copy(sessionId = sessionId)
    }
    
    /**
     * Check if this message belongs to the given session
     */
    fun belongsToSession(sessionId: String): Boolean {
        return this.sessionId == sessionId
    }
}

@Serializable
data class ChatSession(
    val id: String = "",  // UUID session ID
    var timestamp: Long = System.currentTimeMillis(),    // last time the chat screen is opened
    val userId: MimeiId,    // always the appUser
    val receiptId: MimeiId, // whom the app user is chatting with
    var hasNews: Boolean,   // new message hasn't been read.
    var lastMessage: ChatMessage    // the most recent msg, could be from either appUser or the other party
) {
    companion object {
        /**
         * Generate a unique session ID using UUID
         * This creates a new UUID for each session
         */
        fun generateSessionId(): String {
            return UUID.randomUUID().toString()
        }
        
        /**
         * Generate a deterministic session ID based on user IDs
         * This ensures consistent sessionId for the same chat participants
         * Note: sessionId is only stored locally in Room database, not in backend
         */
        fun generateDeterministicSessionId(userId: MimeiId, receiptId: MimeiId): String {
            // Create a deterministic hash based on sorted user IDs to ensure consistency
            // This allows us to recreate the same sessionId when loading from backend
            val sortedIds = listOf(userId, receiptId).sorted()
            val combinedString = "${sortedIds[0]}_${sortedIds[1]}"
            return UUID.nameUUIDFromBytes(combinedString.toByteArray()).toString()
        }
        
        /**
         * Create a new chat session with auto-generated session ID
         */
        fun createSession(
            userId: MimeiId,
            receiptId: MimeiId,
            lastMessage: ChatMessage,
            hasNews: Boolean = false
        ): ChatSession {
            val sessionId = generateSessionId()
            return ChatSession(
                id = sessionId,
                userId = userId,
                receiptId = receiptId,
                lastMessage = lastMessage.copy(sessionId = sessionId),
                hasNews = hasNews
            )
        }
        
        /**
         * Get or create a session for the given participants
         * This ensures we always have a valid sessionId for message tracking
         */
        suspend fun getOrCreateSession(
            userId: MimeiId,
            receiptId: MimeiId,
            chatSessionDao: ChatSessionDao,
            chatMessageDao: ChatMessageDao
        ): String {
            val sessionId = generateDeterministicSessionId(userId, receiptId)
            
            // Try to get existing session
            val existingSession = chatSessionDao.getSessionById(sessionId)
            if (existingSession != null) {
                return sessionId
            }
            
            // Create new session if it doesn't exist
            val newSession = ChatSessionEntity(
                id = sessionId,
                timestamp = System.currentTimeMillis(),
                userId = userId,
                receiptId = receiptId,
                hasNews = false,
                lastMessageId = 0 // Will be updated when first message is added
            )
            chatSessionDao.insertSession(newSession)
            return sessionId
        }
        
        /**
         * Load messages from backend and recreate sessions locally
         * This method handles the case where we need to rebuild the local database
         * from backend data that doesn't contain sessionId
         */
        suspend fun loadMessagesFromBackendAndRecreateSessions(
            messagesFromBackend: List<ChatMessage>, // Messages without sessionId
            userId: MimeiId,
            chatSessionDao: ChatSessionDao,
            chatMessageDao: ChatMessageDao
        ): List<ChatMessage> {
            // Group messages by conversation participants
            val messagesByConversation = messagesFromBackend.groupBy { message ->
                // Create a key based on sorted user IDs to ensure consistency
                val participants = listOf(message.authorId, message.receiptId).sorted()
                "${participants[0]}_${participants[1]}"
            }
            
            val processedMessages = mutableListOf<ChatMessage>()
            
            for ((conversationKey, messages) in messagesByConversation) {
                if (messages.isEmpty()) continue
                
                // Get the first message to determine participants
                val firstMessage = messages.first()
                val otherUserId = if (firstMessage.authorId == userId) {
                    firstMessage.receiptId
                } else {
                    firstMessage.authorId
                }
                
                // Generate sessionId for this conversation
                val sessionId = generateDeterministicSessionId(userId, otherUserId)
                
                // Get or create session
                val existingSession = chatSessionDao.getSessionById(sessionId)
                if (existingSession == null) {
                    // Create new session
                    val latestMessage = messages.maxByOrNull { it.timestamp }
                    val newSession = ChatSessionEntity(
                        id = sessionId,
                        timestamp = latestMessage?.timestamp ?: System.currentTimeMillis(),
                        userId = userId,
                        receiptId = otherUserId,
                        hasNews = false,
                        lastMessageId = 0 // Will be updated when messages are inserted
                    )
                    chatSessionDao.insertSession(newSession)
                }
                
                // Add sessionId to all messages in this conversation
                val messagesWithSessionId = messages.map { it.copy(sessionId = sessionId) }
                processedMessages.addAll(messagesWithSessionId)
            }
            
            return processedMessages
        }
        
        /**
         * Rebuild sessions from existing messages in the database
         * This is useful when the database is recreated and sessions need to be rebuilt
         * Note: This method is simplified since we're using destructive migration
         */
        suspend fun rebuildSessionsFromMessages(
            userId: MimeiId,
            chatSessionDao: ChatSessionDao,
            chatMessageDao: ChatMessageDao
        ) {
            // With destructive migration, the database will be recreated fresh
            // This method is kept for potential future use but is simplified
            // since we don't need to migrate existing data
        }
    }
}

@Entity(
    tableName = "chat_messages",
    indices = [androidx.room.Index(value = ["messageId"], unique = true)]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,  // unique message ID from server - now required
    val receiptId: String,
    val authorId: String,
    val content: String? = null,  // optional content
    val attachments: List<MimeiFileType>? = null,
    val timestamp: Long,
    val sessionId: String? = null,
    val success: Boolean = true,  // whether the message was sent successfully
    val errorMsg: String? = null  // error message if sending failed
)

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String = "",  // UUID session ID
    val timestamp: Long,
    val userId: String,
    val receiptId: String,
    val hasNews: Boolean,
    val lastMessageId: Long
)

fun ChatMessage.toEntity(): ChatMessageEntity {
    return ChatMessageEntity(
        messageId = this.id,
        receiptId = this.receiptId,
        authorId = this.authorId,
        content = this.content,
        attachments = this.attachments,
        timestamp = this.timestamp,
        sessionId = this.sessionId,
        success = this.success,
        errorMsg = this.errorMsg
    )
}

fun ChatMessageEntity.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = this.messageId,
        receiptId = this.receiptId,
        authorId = this.authorId,
        content = this.content,
        attachments = this.attachments,
        timestamp = this.timestamp,
        sessionId = this.sessionId,
        success = this.success,
        errorMsg = this.errorMsg
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
        WHERE sessionId = :sessionId 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    // Load messages by sessionId - all messages in the same session
    suspend fun loadMessagesBySession(sessionId: String, limit: Int): List<ChatMessageEntity>

    @Query("""
        SELECT * FROM chat_messages 
        WHERE sessionId = :sessionId 
        AND timestamp < :beforeTimestamp
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    // Load older messages before a specific timestamp for pagination
    suspend fun loadOlderMessagesBySession(sessionId: String, beforeTimestamp: Long, limit: Int): List<ChatMessageEntity>

    @Query("""
        SELECT * FROM chat_messages 
        WHERE sessionId = :sessionId 
        ORDER BY timestamp ASC
    """)
    // Load all messages in a session in chronological order
    suspend fun loadAllMessagesBySession(sessionId: String): List<ChatMessageEntity>

    @Query("""
        SELECT * FROM chat_messages 
        WHERE sessionId = :sessionId 
        ORDER BY timestamp DESC 
        LIMIT 1
    """)
    // Get the latest message in a session
    suspend fun getLatestMessageBySession(sessionId: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageByMessageId(messageId: String): ChatMessageEntity?

    // Legacy methods for backward compatibility (deprecated)
    @Query("""
        SELECT * FROM chat_messages 
        WHERE (authorId = :userId AND receiptId = :receiptId) 
           OR (authorId = :receiptId AND receiptId = :userId) 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    @Deprecated("Use loadMessagesBySession instead")
    suspend fun loadMessages(userId: String, receiptId: String, limit: Int): List<ChatMessageEntity>

    @Query("""
        SELECT * FROM chat_messages 
        WHERE (authorId = :userId AND receiptId = :receiptId) 
           OR (authorId = :receiptId AND receiptId = :userId) 
        ORDER BY timestamp DESC 
        LIMIT 1
    """)
    @Deprecated("Use getLatestMessageBySession instead")
    suspend fun getLatestMessage(userId: String, receiptId: String): ChatMessageEntity?

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)

    @Query("""
        DELETE FROM chat_messages 
        WHERE (authorId = :userId AND receiptId = :receiptId) 
           OR (authorId = :receiptId AND receiptId = :userId)
    """)
    suspend fun deleteMessages(userId: String, receiptId: String)
    
    @Query("""
        UPDATE chat_messages 
        SET sessionId = :sessionId 
        WHERE (sessionId IS NULL OR sessionId = '')
        AND ((authorId = :userId AND receiptId = :receiptId) 
           OR (authorId = :receiptId AND receiptId = :userId))
    """)
    suspend fun updateMessagesWithSessionId(userId: String, receiptId: String, sessionId: String)

    @Query("DELETE FROM chat_messages WHERE authorId = :authorId AND receiptId = :receiptId AND timestamp = :timestamp")
    suspend fun deleteMessage(authorId: String, receiptId: String, timestamp: Long)
}

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE userId = :userId ORDER BY timestamp DESC")
    // get all chat sessions of the current user
    suspend fun getAllSessions(userId: String): List<ChatSessionEntity>

    @Query("UPDATE chat_sessions SET timestamp = :timestamp, lastMessageId = :lastMessageId, hasNews = :hasNews WHERE id = :sessionId")
    suspend fun updateSession(sessionId: String, timestamp: Long, lastMessageId: Long, hasNews: Boolean)

    // Legacy methods for backward compatibility (deprecated)
    @Query("SELECT * FROM chat_sessions WHERE userId = :userId AND receiptId = :receiptId LIMIT 1")
    @Deprecated("Use getSessionById instead")
    suspend fun getSession(userId: String, receiptId: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET timestamp = :timestamp, lastMessageId = :lastMessageId, hasNews = :hasNews WHERE userId = :userId AND receiptId = :receiptId")
    @Deprecated("Use updateSession with sessionId instead")
    suspend fun updateSession(userId: String, receiptId: String, timestamp: Long, lastMessageId: Long, hasNews: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_sessions WHERE userId = :userId AND receiptId = :receiptId")
    suspend fun deleteSession(userId: String, receiptId: String)
}

@Database(entities = [ChatMessageEntity::class, ChatSessionEntity::class], version = 9)
@TypeConverters(MimeiFileTypeListConverter::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null



        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                                context.applicationContext,
                                ChatDatabase::class.java,
                                "chat_database"
                            )
                            .fallbackToDestructiveMigration(true) // Always force destructive migration - delete and recreate database
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