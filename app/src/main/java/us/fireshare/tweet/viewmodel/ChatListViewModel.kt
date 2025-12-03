package us.fireshare.tweet.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.MimeiId
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatSessionRepository: ChatSessionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    // chat between pair of users
    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> get() = _chatSessions.asStateFlow()

    // Callback for new message notifications
    private var onNewMessageCallback: ((Int) -> Unit)? = null

    fun setOnNewMessageCallback(callback: (Int) -> Unit) {
        onNewMessageCallback = callback
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.tag("ChatListViewModel").d("init: Loading sessions from database")
            chatSessionRepository.getAllSessions().forEach { chatSession ->
                _chatSessions.update { it + chatSession }
            }
            Timber.tag("ChatListViewModel").d("init: Loaded ${_chatSessions.value.size} sessions, now checking for new messages")

            // Check for new messages on initialization
            previewMessages()
        }
    }

    /**
     * Called only from ChatBox screen. Given a message, update corresponding chat session.
     * If a receiptId is given, update its hasNews message flag to false.
     * */
    fun updateSession(msg: ChatMessage?, hasNews: Boolean = false, receiptId: MimeiId? = null) {
        if (msg == null && receiptId == null) {
            return // Nothing to update
        }
        
        // Determine the receiptId for the session to update
        // For outgoing messages: use msg.receiptId (the recipient)
        // For incoming messages: use msg.authorId (the sender)
        // If receiptId parameter is provided, use that
        val targetReceiptId = receiptId ?: run {
            when {
                msg == null -> null
                msg.authorId == appUser.mid -> msg.receiptId // Outgoing: update session with recipient
                else -> msg.authorId // Incoming: update session with sender
            }
        }
        
        if (targetReceiptId == null) {
            return
        }

        // Ensure update happens on main thread for proper Compose observation
        viewModelScope.launch(Dispatchers.Main) {
            val existingSession = _chatSessions.value.find { it.receiptId == targetReceiptId }

            if (existingSession != null) {
                // Update existing session
                val updatedSession = existingSession.copy(
                    hasNews = if (receiptId != null) false else hasNews,
                    lastMessage = msg ?: existingSession.lastMessage,
                    timestamp = msg?.timestamp ?: existingSession.timestamp
                )
                _chatSessions.update { sessions ->
                    (sessions - existingSession + updatedSession).sortedByDescending { it.timestamp }
                }
            } else if (msg != null) {
                // Create new session if message is provided
                _chatSessions.update { sessions ->
                    (sessions + ChatSession(
                        id = ChatSession.generateSessionId(),
                        userId = appUser.mid,
                        receiptId = targetReceiptId,
                        hasNews = hasNews,
                        lastMessage = msg.copy(sessionId = ChatSession.generateSessionId()),
                        timestamp = msg.timestamp
                    )).sortedByDescending { it.timestamp }
                }
            }
        }
    }

    // check if there are new messages on the server. If so, retrieve the last one for UI update.
    suspend fun previewMessages() {
        val newMessages = HproseInstance.checkNewMessages() ?: return
        Timber.tag("checkNewMessages").d("previewMessages: Received ${newMessages.size} messages from server")
        
        // Log details of each message for debugging
        newMessages.forEach { message ->
            Timber.tag("checkNewMessages").d(
                "previewMessages: Message - id=${message.id}, " +
                "authorId=${message.authorId}, receiptId=${message.receiptId}, " +
                "content=${message.content?.take(50)}, " +
                "appUser.mid=${appUser.mid}, " +
                "isIncoming=${message.authorId != appUser.mid}"
            )
        }

        // Filter out messages that already exist in local database
        val trulyNewMessages = chatSessionRepository.filterExistingMessages(newMessages)
        Timber.tag("ChatListViewModel").d("previewMessages: After filtering, ${trulyNewMessages.size} truly new messages")

        if (trulyNewMessages.isEmpty()) {
            Timber.tag("ChatListViewModel").d("previewMessages: No truly new messages, returning")
            return // No truly new messages
        }

        // Notify callback about new messages found
        onNewMessageCallback?.invoke(trulyNewMessages.size)

        // Note: checkNewMessages already filters to only incoming messages and updates timestamps
        // So trulyNewMessages contains only incoming messages with updated timestamps

        // Create sessions for new messages but don't insert the messages
        val updatedSessions =
            chatSessionRepository.mergeMessagesWithSessions(chatSessions.value, trulyNewMessages)

        // Update chat session database (create empty sessions)
        updatedSessions.forEach { chatSession ->
            chatSessionRepository.updateChatSession(
                appUser.mid,
                chatSession.receiptId,
                hasNews = chatSession.hasNews
            )
        }

        // Enhance messages for UI display only (fallback if preview wasn't set)
        val enhancedSessions = updatedSessions.map { chatSession ->
            val lastMessage = chatSession.lastMessage
            if (!lastMessage.attachments.isNullOrEmpty() && lastMessage.content.isNullOrBlank()) {
                val attachmentText = if (lastMessage.authorId == appUser.mid) {
                    context.getString(R.string.attachment_sent)
                } else {
                    context.getString(R.string.attachment_received)
                }
                chatSession.copy(lastMessage = lastMessage.copy(content = attachmentText))
            } else {
                chatSession
            }
        }

        // Update UI sessions with enhanced display
        val existingSessionsMap = _chatSessions.value.associateBy { it.receiptId }
        val updatedChatSessions = _chatSessions.value.toMutableList()
        enhancedSessions.forEach { chatSession ->
            val existingSession = existingSessionsMap[chatSession.receiptId]
            if (existingSession != null) {
                // Check if we should update (different message ID or newer timestamp)
                // Message ID comparison is more reliable since all incoming messages get same currentTime
                val isDifferentMessage = chatSession.lastMessage.id != existingSession.lastMessage.id
                val isNewerTimestamp = chatSession.lastMessage.timestamp > existingSession.lastMessage.timestamp
                if (isDifferentMessage || isNewerTimestamp) {
                    val index = updatedChatSessions.indexOf(existingSession)
                    if (index >= 0) {
                        Timber.tag("ChatListViewModel").d("previewMessages: Updating session for receiptId=${chatSession.receiptId}, oldMessageId=${existingSession.lastMessage.id}, newMessageId=${chatSession.lastMessage.id}")
                        updatedChatSessions[index] = chatSession.copy(
                            id = existingSession.id // Preserve the existing session ID
                        )
                    }
                }
            } else {
                // Add new session
                Timber.tag("ChatListViewModel").d("previewMessages: Adding new session for receiptId=${chatSession.receiptId}")
                updatedChatSessions.add(chatSession)
            }
        }
        // Sort by timestamp descending and update
        _chatSessions.value = updatedChatSessions.sortedByDescending { it.timestamp }
    }

    /**
     * Create a new in-memory chat session for the given user
     * This session will be persisted only when messages are sent
     */
    fun createInMemoryChatSession(receiptId: String) {
        // Check if session already exists
        val existingSession = _chatSessions.value.find { it.receiptId == receiptId }
        if (existingSession != null) {
            return // Session already exists
        }

        // Create a placeholder message for the new session
        val placeholderMessage = ChatMessage(
            id = ChatMessage.generateUniqueId(),
            authorId = appUser.mid,
            receiptId = receiptId,
            content = "New chat started",
            timestamp = System.currentTimeMillis(),
            sessionId = ""
        )

        // Create new in-memory session
        val newSession = ChatSession(
            id = ChatSession.generateSessionId(), // Generate UUID for session
            userId = appUser.mid,
            receiptId = receiptId,
            hasNews = false,
            lastMessage = placeholderMessage.copy(sessionId = ChatSession.generateSessionId()),
            timestamp = System.currentTimeMillis()
        )

        // Add to the list
        _chatSessions.update { sessions ->
            (sessions + newSession).sortedByDescending { it.timestamp }
        }
    }

    /**
     * Delete a chat session from both memory and storage
     */
    fun deleteChatSession(receiptId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Remove from memory first
            _chatSessions.update { sessions ->
                sessions.filterNot { it.receiptId == receiptId }
            }

            // Delete from storage
            chatSessionRepository.deleteChatSession(appUser.mid, receiptId)
        }
    }
}