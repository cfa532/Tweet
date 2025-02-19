package com.fireshare.tweet.chat

import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatMessageDao
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.ChatSessionDao
import com.fireshare.tweet.datamodel.ChatSessionEntity
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.toChatMessage
import com.fireshare.tweet.datamodel.toChatSession

class ChatSessionRepository(
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao
) {

    suspend fun getAllSessions(): List<ChatSession> {
        val sessionEntities = chatSessionDao.getAllSessions(appUser.mid)
        return sessionEntities.mapNotNull { sessionEntity ->
            val lastMessageEntity = chatMessageDao.getMessageById(sessionEntity.lastMessageId)
            lastMessageEntity?.let { sessionEntity.toChatSession(it.toChatMessage()) }
        }
    }

    suspend fun updateChatSession(userId: String, receiptId: String, hasNews: Boolean) {
        val sessionEntity = chatSessionDao.getSession(userId, receiptId)
        val lastMessageEntity = chatMessageDao.getLatestMessage(userId, receiptId)
        lastMessageEntity?.let { messageEntity ->
            if (sessionEntity != null) {
                chatSessionDao.updateSession(
                    userId = userId,
                    receiptId = receiptId,
                    timestamp = messageEntity.timestamp,
                    lastMessageId = messageEntity.id,
                    hasNews = hasNews
                )
            } else {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        userId = userId,
                        receiptId = receiptId,
                        lastMessageId = messageEntity.id,
                        timestamp = messageEntity.timestamp,
                        hasNews = hasNews
                    )
                )
            }
        }
    }

    /**
     * Update existing chat sessions with incoming chat message.
     * Chat message is identified by its Normalized pair of authorId and receiptId.
     * The session's author is always the current app user, and its receipt is the one
     * engaging in conversation with the appUser.
     * */
    fun mergeMessagesWithSessions(
        existingSessions: List<ChatSession>,
        newMessages: List<ChatMessage>
    ): List<ChatSession> {

        // a map using senderId and receiptId as key, and ChatMessage as value
        val messageMap = mutableMapOf<Pair<MimeiId, MimeiId>, ChatMessage>()
        fun normalizedKey(message: ChatMessage): Pair<MimeiId, MimeiId> {
            return if (message.receiptId < message.authorId) {
                Pair(message.receiptId, message.authorId)
            } else {
                Pair(message.authorId, message.receiptId)
            }
        }
        // Add existing messages to the map
        existingSessions.forEach { session ->
            val message = session.lastMessage
            val key = normalizedKey(message)
            messageMap[key] = message
        }

        // Merge new messages into the map, by replacing old last messages.
        newMessages.forEach { message ->
            val key = normalizedKey(message)
            val existingMessage = messageMap[key]
            if (existingMessage == null || message.timestamp > existingMessage.timestamp) {
                messageMap[key] = message
            }
        }

        val updatedSessions = existingSessions.toMutableList()
        messageMap.forEach { (key, msg) ->
            val es =
                existingSessions.find { it.receiptId == key.first || it.receiptId == key.second }
            if (es == null) {
                // a new session is created.
                updatedSessions.add(
                    ChatSession(
                        timestamp = msg.timestamp,
                        userId = appUser.mid,
                        receiptId = if (key.first == appUser.mid) key.second else key.first,
                        hasNews = true,
                        lastMessage = msg
                    )
                )
            } else {
                if (msg.timestamp > es.lastMessage.timestamp) {
                    // existing session is updated with new message.
                    updatedSessions.remove(es)
                    updatedSessions.add(
                        es.copy(
                            lastMessage = msg,
                            timestamp = msg.timestamp,
                            hasNews = true
                        )
                    )
                }
            }
        }
        return updatedSessions.toList()
    }
}