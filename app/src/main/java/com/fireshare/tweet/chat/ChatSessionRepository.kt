package com.fireshare.tweet.chat

import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatMessageDao
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.ChatSessionDao
import com.fireshare.tweet.datamodel.toChatMessage
import com.fireshare.tweet.datamodel.toChatSession
import com.fireshare.tweet.datamodel.toEntity

class ChatSessionRepository(
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao
) {

    suspend fun getAllSessions(): List<ChatSession> {
        val sessionEntities = chatSessionDao.getAllSessions()
        return sessionEntities.mapNotNull { sessionEntity ->
            val lastMessageEntity = chatMessageDao.getMessageById(sessionEntity.lastMessageId)
            lastMessageEntity?.let { sessionEntity.toChatSession(it.toChatMessage()) }
        }
    }

    suspend fun updateLastMessage(userId: String, receiptId: String) {
        val sessionEntity = chatSessionDao.getSession(userId, receiptId)
        sessionEntity?.let {
            val lastMessageEntity = chatMessageDao.getLatestMessage(userId, receiptId)
            lastMessageEntity?.let { messageEntity ->
                val updatedSession = sessionEntity.copy(
                    lastMessageId = messageEntity.id,
                    timestamp = messageEntity.timestamp,
                    hasNews = true
                )
                chatSessionDao.insertSession(updatedSession)
            }
        }
    }
}