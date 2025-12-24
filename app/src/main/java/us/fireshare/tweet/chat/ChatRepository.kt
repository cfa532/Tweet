package us.fireshare.tweet.chat

import timber.log.Timber
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDao
import us.fireshare.tweet.datamodel.ChatMessageEntity
import us.fireshare.tweet.datamodel.toEntity

class ChatRepository(private val chatMessageDao: ChatMessageDao) {

    suspend fun loadMessagesBySession(sessionId: String, limit: Int): List<ChatMessageEntity> {
        val messages = chatMessageDao.loadMessagesBySession(sessionId, limit)
        Timber.tag("ChatRepository").d("loadMessagesBySession: sessionId=$sessionId, limit=$limit, found ${messages.size} messages")
        return messages
    }

    suspend fun loadOlderMessagesBySession(sessionId: String, beforeTimestamp: Long, limit: Int): List<ChatMessageEntity> {
        return chatMessageDao.loadOlderMessagesBySession(sessionId, beforeTimestamp, limit)
    }

    suspend fun insertMessage(message: ChatMessage) {
        val entity = message.toEntity()
        Timber.tag("ChatRepository").d("insertMessage: id=${message.id}, sessionId=${entity.sessionId}, authorId=${entity.authorId}, receiptId=${entity.receiptId}")
        chatMessageDao.insertMessage(entity)
        Timber.tag("ChatRepository").d("insertMessage: Successfully inserted message ${message.id}")
    }

    suspend fun insertMessages(messages: List<ChatMessage>) {
        val messageEntities = messages.map { it.toEntity() }
        chatMessageDao.insertMessages(messageEntities)
    }
}
