package com.fireshare.tweet.datamodel

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val receiptId: MimeiId,
    val authorId: MimeiId,
    val content: String,
    val timestamp: Long
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: String,
    val authorId: String,
    val content: String,
    val timestamp: Long
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

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE receiptId = :receiptId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun loadMessages(receiptId: String, limit: Int): List<ChatMessageEntity>

    @Query("""
        SELECT * FROM chat_messages 
        WHERE id IN (
            SELECT MAX(id) FROM chat_messages 
            GROUP BY receiptId
        )
        ORDER BY timestamp DESC
    """)
    suspend fun loadMostRecentMessages(): List<ChatMessageEntity>
}

@Database(entities = [ChatMessageEntity::class], version = 1)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
}