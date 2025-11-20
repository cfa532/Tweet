package us.fireshare.tweet.chat

import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDao
import us.fireshare.tweet.datamodel.ChatMessageEntity
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.ChatSessionDao
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
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
                val previewEntity = messageEntity
                    .copy(sessionId = sessionEntity.id)
                    .withAttachmentPreview()
                sessionEntity.toChatSession(previewEntity.toChatMessage())
            }
        }
    }

    suspend fun updateChatSession(userId: String, receiptId: String, hasNews: Boolean) {
        // Get or create session ID for this conversation
        val sessionId = getOrCreateSessionId(userId, receiptId)

        // Get the latest message for this conversation
        val lastMessageEntity = chatMessageDao.getLatestMessageBySession(sessionId)
        lastMessageEntity?.let { messageEntity ->
            updateSessionWithEntity(sessionId, messageEntity, hasNews)
        }
    }

    suspend fun updateChatSessionWithMessage(
        userId: String,
        receiptId: String,
        message: ChatMessage,
        hasNews: Boolean
    ): ChatMessage {
        val sessionId = getOrCreateSessionId(userId, receiptId)
        val normalizedMessage = if (message.sessionId == sessionId) {
            message
        } else {
            message.copy(sessionId = sessionId)
        }

        val previewMessage = normalizedMessage.withAttachmentPreview()
        val existingEntity = chatMessageDao.getMessageByMessageId(previewMessage.id)

        if (existingEntity != null) {
            val previewEntity = existingEntity.copy(
                content = previewMessage.content,
                attachments = previewMessage.attachments,
                timestamp = previewMessage.timestamp,
                sessionId = sessionId
            )
            chatMessageDao.insertMessage(previewEntity)
            updateSessionWithEntity(sessionId, previewEntity, hasNews)
            return previewEntity.toChatMessage()
        }

        chatMessageDao.insertMessage(previewMessage.toEntity())
        chatMessageDao.getMessageByMessageId(previewMessage.id)?.let { inserted ->
            val entityWithSession = inserted.copy(sessionId = sessionId)
            chatMessageDao.insertMessage(entityWithSession)
            updateSessionWithEntity(sessionId, entityWithSession, hasNews)
        }
        return previewMessage
    }

    private suspend fun updateSessionWithEntity(
        sessionId: String,
        messageEntity: ChatMessageEntity,
        hasNews: Boolean
    ) {
        // Update existing session using sessionId
        chatSessionDao.updateSession(
            sessionId = sessionId,
            timestamp = messageEntity.timestamp,
            lastMessageId = messageEntity.id,
            hasNews = hasNews
        )

        // Ensure message has sessionId set for consistency
        if (messageEntity.sessionId != sessionId) {
            chatMessageDao.insertMessage(messageEntity.copy(sessionId = sessionId))
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
     * Get or create session ID for a conversation
     */
    suspend fun getOrCreateSessionId(userId: String, receiptId: String): String {
        return ChatSession.getOrCreateSession(userId, receiptId, chatSessionDao)
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
        // Compare by message ID first (more reliable), then by timestamp
        // This is important because previewMessages sets all incoming messages to the same currentTime
        newMessages.forEach { message ->
            val key = normalizedKey(message)
            val existingMessage = messageMap[key]
            if (existingMessage == null) {
                messageMap[key] = message
            } else {
                // Update if message ID is different (new message) or if timestamp is newer
                val isDifferentMessage = message.id != existingMessage.id
                val isNewerTimestamp = message.timestamp > existingMessage.timestamp
                if (isDifferentMessage || isNewerTimestamp) {
                    messageMap[key] = message
                }
            }
        }

        val updatedSessions = existingSessions.toMutableList()
        messageMap.forEach { (key, rawMessage) ->
            val msg = rawMessage.withAttachmentPreview()
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
                // Update if message ID is different (new message) or if timestamp is newer
                // Message ID comparison is more reliable since previewMessages sets all incoming messages to same currentTime
                val isDifferentMessage = msg.id != es.lastMessage.id
                val isNewerTimestamp = msg.timestamp > es.lastMessage.timestamp
                if (isDifferentMessage || isNewerTimestamp) {
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

    private fun ChatMessage.withAttachmentPreview(): ChatMessage {
        val preview = buildAttachmentPreview(authorId, attachments) ?: return this
        return copy(content = preview)
    }

    private fun ChatMessageEntity.withAttachmentPreview(): ChatMessageEntity {
        val preview = buildAttachmentPreview(authorId, attachments) ?: return this
        return copy(content = preview)
    }

    private fun buildAttachmentPreview(authorId: MimeiId, attachments: List<MimeiFileType>?): String? {
        val attachment = attachments?.firstOrNull() ?: return null
        val baseLabel = when (attachment.type) {
            MediaType.Image -> "Image"
            MediaType.Video, MediaType.HLS_VIDEO -> "Video"
            MediaType.Audio -> "Audio"
            MediaType.PDF, MediaType.Word, MediaType.Excel, MediaType.PPT, MediaType.Txt, MediaType.Html -> "Document"
            MediaType.Zip -> "Archive"
            MediaType.Unknown -> "Attachment"
        }
        val direction = if (authorId == appUser.mid) "sent" else "received"
        return "$baseLabel $direction"
    }
}