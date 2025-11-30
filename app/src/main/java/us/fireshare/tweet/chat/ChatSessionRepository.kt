package us.fireshare.tweet.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
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
import javax.inject.Inject

class ChatSessionRepository @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao,
    @ApplicationContext private val context: Context
) {

    suspend fun getAllSessions(): List<ChatSession> {
        val sessionEntities = chatSessionDao.getAllSessions(appUser.mid)
        return sessionEntities.mapNotNull { sessionEntity ->
            val lastMessageEntity = chatMessageDao.getMessageById(sessionEntity.lastMessageId)
            lastMessageEntity?.let { messageEntity ->
                val message = messageEntity.copy(sessionId = sessionEntity.id).toChatMessage()
                // Create preview message for display (original message content is preserved in database)
                val previewMessage = message.withAttachmentPreview(context)
                sessionEntity.toChatSession(previewMessage)
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

        // Store the original message (without preview text) in the database
        val existingEntity = chatMessageDao.getMessageByMessageId(normalizedMessage.id)
        if (existingEntity != null) {
            val entityToStore = existingEntity.copy(
                content = normalizedMessage.content, // Use original content, not preview
                attachments = normalizedMessage.attachments,
                timestamp = normalizedMessage.timestamp,
                sessionId = sessionId
            )
            chatMessageDao.insertMessage(entityToStore)
            // Create preview message for session's lastMessage (for display only)
            val previewMessage = normalizedMessage.withAttachmentPreview(context)
            updateSessionWithEntity(sessionId, entityToStore, hasNews)
            return previewMessage
        }

        // Store original message in database
        chatMessageDao.insertMessage(normalizedMessage.toEntity())
        chatMessageDao.getMessageByMessageId(normalizedMessage.id)?.let { inserted ->
            val entityWithSession = inserted.copy(sessionId = sessionId)
            chatMessageDao.insertMessage(entityWithSession)
            // Create preview message for session's lastMessage (for display only)
            val previewMessage = normalizedMessage.withAttachmentPreview(context)
            updateSessionWithEntity(sessionId, entityWithSession, hasNews)
            return previewMessage
        }
        // Create preview message for session's lastMessage (for display only)
        return normalizedMessage.withAttachmentPreview(context)
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
            // Create preview message for display (original message content is preserved)
            val msg = rawMessage.withAttachmentPreview(context)
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

    private fun ChatMessage.withAttachmentPreview(context: Context): ChatMessage {
        val preview = buildAttachmentPreview(context, authorId, attachments) ?: return this
        return copy(content = preview)
    }

    private fun buildAttachmentPreview(context: Context, authorId: MimeiId, attachments: List<MimeiFileType>?): String? {
        val attachment = attachments?.firstOrNull() ?: return null
        val stringResId = when (attachment.type) {
            MediaType.Image -> if (authorId == appUser.mid) R.string.image_sent else R.string.image_received
            MediaType.Video, MediaType.HLS_VIDEO -> if (authorId == appUser.mid) R.string.video_sent else R.string.video_received
            MediaType.Audio -> if (authorId == appUser.mid) R.string.audio_sent else R.string.audio_received
            MediaType.PDF, MediaType.Word, MediaType.Excel, MediaType.PPT, MediaType.Txt, MediaType.Html -> 
                if (authorId == appUser.mid) R.string.document_sent else R.string.document_received
            MediaType.Zip -> if (authorId == appUser.mid) R.string.archive_sent else R.string.archive_received
            MediaType.Unknown -> if (authorId == appUser.mid) R.string.attachment_sent else R.string.attachment_received
        }
        return context.getString(stringResId)
    }
}