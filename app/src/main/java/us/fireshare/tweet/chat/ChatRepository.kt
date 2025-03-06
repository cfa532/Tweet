package us.fireshare.tweet.chat

import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDao
import us.fireshare.tweet.datamodel.ChatMessageEntity
import us.fireshare.tweet.datamodel.toEntity
import com.google.gson.Gson

class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    suspend fun loadMessages(userId: String, receiptId: String, limit: Int): List<ChatMessageEntity> {
        return chatMessageDao.loadMessages(userId, receiptId, limit)
    }

    suspend fun insertMessage(message: ChatMessage) {
        chatMessageDao.insertMessage(message.toEntity())
    }

    suspend fun insertMessages(messages: List<ChatMessage>) {
        val gson = Gson()
        val messageEntities = mutableListOf<ChatMessageEntity>()
        for(i in messages.indices) {
            val str = gson.toJson(messages[i])
            val message = gson.fromJson(str, ChatMessage::class.java)
            messageEntities.add(message.toEntity())
        }
        chatMessageDao.insertMessages(messageEntities)
    }
}
