package com.fireshare.tweet

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId

class LocalDatabase {
    private val messageStore = mutableMapOf<MimeiId, MutableList<ChatMessage>>()

    fun saveMessage(receiptId: MimeiId, message: ChatMessage) {
        val messages = messageStore.getOrPut(receiptId) { mutableListOf() }
        messages.add(0, message) // Add message to the beginning of the list
    }

    fun loadMessages(receiptId: MimeiId, limit: Int): List<ChatMessage> {
        return messageStore[receiptId]?.take(limit) ?: emptyList()
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: String,
    val authorId: String,
    val content: String,
    val timestamp: Long
)

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE receiptId = :receiptId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun loadMessages(receiptId: String, limit: Int): List<ChatMessageEntity>
}

@Database(entities = [ChatMessageEntity::class], version = 1)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
}