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
import com.google.gson.Gson

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
     * Chat message is identified by its pair of authorId and receiptId. Depending on
     * the direction of message flow, the pair change switch. Whereas the session's
     * author is always the current app user, and its receipt is the one engaging in
     * conversation with the appUser.
     * */
    fun mergeMessagesWithSessions(
        existingSessions: List<ChatSession>,
        newMessages: List<ChatMessage>
    ): List<ChatSession> {
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

        // Normalize the new messages and merge them into the map
        val gson = Gson()
        for (i in newMessages.indices) {
            val json = gson.toJson(newMessages[i])
            val msg = gson.fromJson(json, ChatMessage::class.java)
            val key = normalizedKey(msg)
            val existingMessage = messageMap[key]
            if (existingMessage == null || msg.timestamp > existingMessage.timestamp) {
                messageMap[key] = msg
            }
        }

        // Create a list of ChatSession from the merged messages
        return existingSessions.map { existingSession ->
            val key = normalizedKey(existingSession.lastMessage)
            val updatedLastMessage = messageMap[key] ?: existingSession.lastMessage // Use existing if not in messageMap
            val receiptId = if (updatedLastMessage.authorId == appUser.mid) {
                updatedLastMessage.receiptId
            } else updatedLastMessage.authorId

            // if the session receipt is author of any new message
            val hasNews = hasNews(newMessages, receiptId)
            ChatSession(
                timestamp = updatedLastMessage.timestamp,
                userId = appUser.mid,
                receiptId = receiptId,
                hasNews = hasNews,
                lastMessage = updatedLastMessage
            )
        }
    }

    /**
     * If there is new message from a certain user, indicate it in the ChatSession
     * A mail badge will be displayed on that user's avatar
     * */
    private fun hasNews(newMessages: List<ChatMessage>, receiptId: MimeiId): Boolean {
        val gson = Gson()
        for (i in newMessages.indices) {
            val json = gson.toJson(newMessages[i])
            val msg = gson.fromJson(json, ChatMessage::class.java)
            if (msg.authorId == receiptId)
                return true
        }
        return false
    }
}