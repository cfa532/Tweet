package com.fireshare.tweet.datamodel

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val receiptId: MimeiId,     // receiver of the message
    val authorId: MimeiId,      // author of the message
    val content: String,
    val timestamp: Long
)

@Serializable
data class ChatSession(
    val timestamp: Long,    // last time the chat screen is opened
    val userId: MimeiId,    // always the appUser
    val receiptId: MimeiId, // whom the app user is chatting with
    val hasNews: Boolean,   // new message hasn't been read.
    val lastMessage: ChatMessage    // could be from either appUser or the other party
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: String,
    val authorId: String,
    val content: String,
    val timestamp: Long
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
        timestamp = this.timestamp
    )
}

fun ChatMessageEntity.toChatMessage(): ChatMessage {
    return ChatMessage(
        receiptId = this.receiptId,
        authorId = this.authorId,
        content = this.content,
        timestamp = this.timestamp
    )
}

fun ChatSession.toEntity(lastMessageId: Long): ChatSessionEntity {
    return ChatSessionEntity(
        timestamp = this.timestamp,
        userId = this.userId,
        receiptId = this.receiptId,
        hasNews = this.hasNews,
        lastMessageId = lastMessageId
    )
}

fun ChatSessionEntity.toChatSession(lastMessage: ChatMessage): ChatSession {
    return ChatSession(
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

@Database(entities = [ChatMessageEntity::class, ChatSessionEntity::class], version = 1)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
}