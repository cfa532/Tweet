package us.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.MimeiId
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatSessionRepository: ChatSessionRepository
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
        Timber.tag("ChatListViewModel").d("previewMessages: Starting to check for new messages")
        val newMessages = HproseInstance.checkNewMessages()
        if (newMessages == null) {
            Timber.tag("ChatListViewModel").d("previewMessages: checkNewMessages returned null")
            return
        }
        Timber.tag("ChatListViewModel").d("previewMessages: Found ${newMessages.size} messages from server")

        // Filter out messages that already exist in local database
        val trulyNewMessages = chatSessionRepository.filterExistingMessages(newMessages)
        Timber.tag("ChatListViewModel").d("previewMessages: After filtering, ${trulyNewMessages.size} truly new messages")

        if (trulyNewMessages.isEmpty()) {
            Timber.tag("ChatListViewModel").d("previewMessages: No truly new messages, returning")
            return // No truly new messages
        }

        // Notify callback about new messages found
        onNewMessageCallback?.invoke(trulyNewMessages.size)

        // Update timestamps with local system time for received messages
        val currentTime = System.currentTimeMillis()
        val updatedNewMessages = trulyNewMessages.map { message ->
            if (message.authorId != appUser.mid) {
                // Update timestamp for incoming messages with local time
                message.copy(timestamp = currentTime)
            } else {
                message
            }
        }

        // Create sessions for new messages but don't insert the messages
        val updatedSessions =
            chatSessionRepository.mergeMessagesWithSessions(chatSessions.value, updatedNewMessages)

        // Update chat session database (create empty sessions)
        updatedSessions.forEach { chatSession ->
            chatSessionRepository.updateChatSession(
                appUser.mid,
                chatSession.receiptId,
                hasNews = chatSession.hasNews
            )
        }

        // Enhance messages for UI display only
        val enhancedSessions = updatedSessions.map { chatSession ->
            val lastMessage = chatSession.lastMessage
            if (!lastMessage.attachments.isNullOrEmpty() && lastMessage.content.isNullOrBlank()) {
                val attachmentText = if (lastMessage.authorId == appUser.mid) {
                    "Attachment sent"
                } else {
                    "Attachment received"
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
                // Update existing session directly
                val index = updatedChatSessions.indexOf(existingSession)
                updatedChatSessions[index] = existingSession.copy(
                    hasNews = chatSession.hasNews,
                    lastMessage = chatSession.lastMessage,
                    timestamp = chatSession.timestamp
                )
            } else {
                // Add new session
                updatedChatSessions.add(chatSession)
            }
        }
        _chatSessions.value = updatedChatSessions
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