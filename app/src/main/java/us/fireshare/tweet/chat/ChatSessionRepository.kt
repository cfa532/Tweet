package us.fireshare.tweet.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
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
     * 
     * This implementation matches the iOS version which determines the partner ID
     * directly from the message (authorId for incoming, receiptId for outgoing).
     * */
    fun mergeMessagesWithSessions(
        existingSessions: List<ChatSession>,
        newMessages: List<ChatMessage>
    ): List<ChatSession> {
        // Group messages by conversation partner (matching iOS implementation)
        // For incoming messages: partnerId = authorId (the sender)
        // For outgoing messages: partnerId = receiptId (the recipient)
        val messagesByPartner = newMessages.groupBy { message ->
            if (message.authorId == appUser.mid) {
                message.receiptId  // Outgoing: use receiptId (recipient)
            } else {
                message.authorId   // Incoming: use authorId (sender)
            }
        }
        
        val updatedSessions = existingSessions.toMutableList()
        
        // Process each group of messages by partner
        messagesByPartner.forEach { (partnerId, messages) ->
            // Use the last message from the group (newest message)
            val lastMessage = messages.maxByOrNull { it.timestamp } ?: return@forEach
            
            // Create preview message for display (original message content is preserved)
            val msg = lastMessage.withAttachmentPreview(context)
            
            Timber.tag("ChatSessionRepository").d(
                "mergeMessagesWithSessions: Processing message - " +
                "authorId=${lastMessage.authorId}, receiptId=${lastMessage.receiptId}, " +
                "partnerId=$partnerId, appUser.mid=${appUser.mid}, " +
                "isIncoming=${lastMessage.authorId != appUser.mid}"
            )
            
            // Find existing session by partnerId (the other user)
            val existingSession = existingSessions.find { it.receiptId == partnerId }
            
            if (existingSession == null) {
                // Create new session
                Timber.tag("ChatSessionRepository").d(
                    "mergeMessagesWithSessions: Creating new session for partnerId=$partnerId"
                )
                updatedSessions.add(
                    ChatSession(
                        id = ChatSession.generateSessionId(), // Generate UUID for session
                        timestamp = msg.timestamp,
                        userId = appUser.mid,
                        receiptId = partnerId,
                        hasNews = true,
                        lastMessage = msg.copy(sessionId = ChatSession.generateSessionId()) // Will be updated when session is saved
                    )
                )
            } else {
                // Update existing session if message ID is different (new message) or if timestamp is newer
                // Message ID comparison is more reliable since previewMessages sets all incoming messages to same currentTime
                val isDifferentMessage = msg.id != existingSession.lastMessage.id
                val isNewerTimestamp = msg.timestamp > existingSession.lastMessage.timestamp
                if (isDifferentMessage || isNewerTimestamp) {
                    Timber.tag("ChatSessionRepository").d(
                        "mergeMessagesWithSessions: Updating existing session for partnerId=$partnerId, " +
                        "oldMessageId=${existingSession.lastMessage.id}, newMessageId=${msg.id}"
                    )
                    val index = updatedSessions.indexOf(existingSession)
                    if (index >= 0) {
                        updatedSessions[index] = existingSession.copy(
                            lastMessage = msg.copy(sessionId = existingSession.id),
                            timestamp = msg.timestamp,
                            hasNews = true
                        )
                    }
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