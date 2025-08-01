package us.fireshare.tweet.chat

import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDao
import us.fireshare.tweet.datamodel.ChatMessageEntity
import us.fireshare.tweet.datamodel.toEntity

class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    suspend fun loadMessages(userId: String, receiptId: String, limit: Int): List<ChatMessageEntity> {
        return chatMessageDao.loadMessages(userId, receiptId, limit)
    }

    suspend fun insertMessage(message: ChatMessage) {
        chatMessageDao.insertMessage(message.toEntity())
    }

    suspend fun insertMessages(messages: List<ChatMessage>) {
        val messageEntities = messages.map { it.toEntity() }
        chatMessageDao.insertMessages(messageEntities)
    }
}
