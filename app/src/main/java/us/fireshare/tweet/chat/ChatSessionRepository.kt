package us.fireshare.tweet.chat

import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDao
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.ChatSessionDao
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.toChatMessage
import us.fireshare.tweet.datamodel.toChatSession
import us.fireshare.tweet.datamodel.toEntity

class ChatSessionRepository(
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao
) {

    suspend fun getAllSessions(): List<ChatSession> {
        val sessionEntities = chatSessionDao.getAllSessions(appUser.mid)
        return sessionEntities.mapNotNull { sessionEntity ->
            val lastMessageEntity = chatMessageDao.getMessageById(sessionEntity.lastMessageId)
            lastMessageEntity?.let { messageEntity ->
                val chatMessage = messageEntity.toChatMessage().copy(sessionId = sessionEntity.id)
                sessionEntity.toChatSession(chatMessage)
            }
        }
    }

    suspend fun updateChatSession(userId: String, receiptId: String, hasNews: Boolean) {
        // Get or create session ID for this conversation
        val sessionId = getOrCreateSessionId(userId, receiptId)
        
        // Get the latest message for this conversation
        val lastMessageEntity = chatMessageDao.getLatestMessageBySession(sessionId)
        lastMessageEntity?.let { messageEntity ->
            // Update existing session using sessionId
            chatSessionDao.updateSession(
                sessionId = sessionId,
                timestamp = messageEntity.timestamp,
                lastMessageId = messageEntity.id,
                hasNews = hasNews
            )

            // Update message with sessionId if not already set
            if (messageEntity.sessionId == null) {
                val updatedMessage = messageEntity.copy(sessionId = sessionId)
                chatMessageDao.insertMessage(updatedMessage)
            }
        }
    }

    /**
     * Update an existing chat session without creating a new one
     * This is used when we just want to mark a session as read
     */
    suspend fun updateExistingChatSession(userId: String, receiptId: String, hasNews: Boolean) {
        val sessionId = getOrCreateSessionId(userId, receiptId)
        val sessionEntity = chatSessionDao.getSessionById(sessionId)
        sessionEntity?.let {
            // Only update if session exists
            chatSessionDao.updateSession(
                sessionId = sessionId,
                timestamp = it.timestamp,
                lastMessageId = it.lastMessageId,
                hasNews = hasNews
            )
        }
    }

    /**
     * Update a specific message in the database
     */
    suspend fun updateMessage(message: ChatMessage) {
        chatMessageDao.insertMessage(message.toEntity())
    }

    /**
     * Update messages with the correct sessionId for a conversation
     */
    suspend fun updateMessagesWithSessionId(userId: String, receiptId: String, sessionId: String) {
        chatMessageDao.updateMessagesWithSessionId(userId, receiptId, sessionId)
    }

    /**
     * Get or create session ID for a conversation
     */
    suspend fun getOrCreateSessionId(userId: String, receiptId: String): String {
        return ChatSession.getOrCreateSession(userId, receiptId, chatSessionDao, chatMessageDao)
    }

    /**
     * Get a chat session by userId and receiptId
     */
    suspend fun getChatSession(userId: String, receiptId: String): ChatSession? {
        val sessionId = getOrCreateSessionId(userId, receiptId)
        val sessionEntity = chatSessionDao.getSessionById(sessionId) ?: return null
        val lastMessage =
            chatMessageDao.getLatestMessageBySession(sessionEntity.id)?.toChatMessage()
                ?: return null
        return sessionEntity.toChatSession(lastMessage)
    }

    /**
     * Filter out messages that already exist in the local database
     */
    suspend fun filterExistingMessages(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.filter { message ->
            val existingMessage = chatMessageDao.getMessageByMessageId(message.id)
            existingMessage == null
        }
    }

    /**
     * Delete a chat session and all its messages
     */
    suspend fun deleteChatSession(userId: String, receiptId: String) {
        val sessionId = getOrCreateSessionId(userId, receiptId)
        
        // Delete the session from chat session database
        chatSessionDao.deleteSession(sessionId)

        // Delete all messages using the sessionId
        chatMessageDao.deleteMessagesBySession(sessionId)
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
                        id = ChatSession.generateSessionId(), // Generate UUID for session
                        timestamp = msg.timestamp,
                        userId = appUser.mid,
                        receiptId = if (key.first == appUser.mid) key.second else key.first,
                        hasNews = true,
                        lastMessage = msg.copy(sessionId = ChatSession.generateSessionId()) // Will be updated when session is saved
                    )
                )
            } else {
                if (msg.timestamp > es.lastMessage.timestamp) {
                    // existing session is updated with new message.
                    updatedSessions.remove(es)
                    updatedSessions.add(
                        es.copy(
                            lastMessage = msg.copy(sessionId = es.id),
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