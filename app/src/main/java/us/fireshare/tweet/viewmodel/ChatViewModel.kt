package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.chat.ChatRepository
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.toChatMessage
import us.fireshare.tweet.service.SendChatMessageWorker

@HiltViewModel( assistedFactory = ChatViewModel.ChatViewModelFactory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted val receiptId: MimeiId,
    private val chatRepository: ChatRepository,
    private val chatSessionRepository: ChatSessionRepository
    ): ViewModel() {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages.asStateFlow()

    private val _receipt =
        MutableStateFlow<User>(User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl))
    val receipt: StateFlow<User> get() = _receipt.asStateFlow()

    var message = mutableStateOf("")
    var selectedAttachment = mutableStateOf<Uri?>(null)
    var chatListViewModel: ChatListViewModel? = null

    // Toast message state
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> get() = _toastMessage.asStateFlow()

    // Pagination state
    private val _isLoadingOlderMessages = MutableStateFlow(false)
    val isLoadingOlderMessages: StateFlow<Boolean> get() = _isLoadingOlderMessages.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(true)
    val hasMoreMessages: StateFlow<Boolean> get() = _hasMoreMessages.asStateFlow()

    companion object {
        private const val MESSAGES_PER_PAGE = 10
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _receipt.value = HproseInstance.getUser(receiptId)
                ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)

            // Load only the latest 10 messages from local database
            _chatMessages.value = loadLatestMessages(receiptId)
                .sortedBy { it.timestamp }

            // get unread messages from network
            fetchNewMessage()
        }
    }

    suspend fun sendMessage(content: String, attachmentUri: Uri? = null, context: Context? = null) {
        // If there's an attachment, use background worker
        if (attachmentUri != null && context != null) {
            sendMessageWithAttachment(content, attachmentUri, context)
            return
        }

        // For text-only messages, use the original synchronous approach
        sendTextMessage(content)
    }

    private suspend fun sendTextMessage(content: String) {
        val messageContent = if (content.trim().isNotBlank()) content.trim() else null

        // Get or create session ID for this conversation
        val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

        val message = ChatMessage(
            id = ChatMessage.generateUniqueId(),
            receiptId = receiptId,
            authorId = appUser.mid,
            timestamp = System.currentTimeMillis(),
            content = messageContent,
            attachments = null,
            sessionId = sessionId
        )

        try {
            // Add message to UI immediately for instant feedback
            _chatMessages.value += message
            chatRepository.insertMessage(message)
            HproseInstance.sendMessage(receiptId, message)
            chatSessionRepository.updateChatSession(
                appUser.mid,
                receiptId,
                hasNews = false
            )
            Timber.tag("ChatViewModel")
                .d("sendTextMessage calling updateSession with message: ${message.content}, authorId: ${message.authorId}")
            chatListViewModel?.updateSession(message, hasNews = false)
        } catch (e: Exception) {
            Timber.tag("ChatViewModel").e(e, "Error sending text message")
            _toastMessage.value = "Failed to send message: ${e.message}"
        }
    }

    private suspend fun sendMessageWithAttachment(
        content: String,
        attachmentUri: Uri,
        context: Context
    ) {
        // Show sending status toast
        _toastMessage.value = "Uploading attachment in background..."

        // Get or create session ID for this conversation
        val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

        // Create work request for background processing
        val workRequest = OneTimeWorkRequestBuilder<SendChatMessageWorker>()
            .setInputData(
                workDataOf(
                    "receiptId" to receiptId,
                    "content" to content,
                    "attachmentUri" to attachmentUri.toString(),
                    "sessionId" to sessionId,
                    "messageTimestamp" to System.currentTimeMillis()
                )
            )
            .build()

        // Enqueue the work
        WorkManager.getInstance(context).enqueue(workRequest)

        // Listen for worker completion events
        viewModelScope.launch {
            TweetNotificationCenter.events.collect { event ->
                when (event) {
                    is TweetEvent.ChatMessageSent -> {
                        if (event.message.receiptId == receiptId) {

                            // Add the real message to UI and database
                            _chatMessages.update { messages ->
                                (messages + event.message).sortedBy { it.timestamp }
                            }

                            // Insert message to database
                            chatRepository.insertMessage(event.message)

                            // Update chat session
                            chatSessionRepository.updateChatSession(
                                appUser.mid,
                                receiptId,
                                hasNews = false
                            )
                            // If message has attachments but no content, add descriptive text
                            val messageWithContent =
                                if (!event.message.attachments.isNullOrEmpty() && event.message.content.isNullOrBlank()) {
                                    event.message.copy(content = "Attachment sent")
                                } else {
                                    event.message
                                }
                            chatListViewModel?.updateSession(messageWithContent, hasNews = false)

                            // No success toast for attachment messages - only show failure toasts
                        }
                    }

                    is TweetEvent.ChatMessageSendFailed -> {
                        // Show error toast
                        _toastMessage.value = "Failed to send message: ${event.error}"
                    }

                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }
    
    fun scrollToBottom() {
        // This will be called from the UI to trigger scroll to bottom
        // The actual scrolling is handled in the ChatScreen
    }

    /**
     * Check new messages on writable host of an user.
     * */
    suspend fun fetchNewMessage(numOfMsgs: Int = 500) {
        val fetchedMessages = HproseInstance.fetchMessages(receiptId) ?: return
        val news = fetchedMessages.toMutableList()
        Timber.tag("ChatViewModel").d("fetchNewMessage fetched ${news.size} messages $receiptId")
        if (news.isNotEmpty()) {
            // Get or create session ID for this conversation
            val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

            // Update timestamps with local system time for received messages and assign session ID
            val currentTime = System.currentTimeMillis()
            val updatedNews = news.map { message ->
                if (message.authorId != appUser.mid) {
                    // Update timestamp for incoming messages with local time and assign session ID
                    message.copy(timestamp = currentTime, sessionId = sessionId)
                } else {
                    // Assign session ID to outgoing messages
                    message.copy(sessionId = sessionId)
                }
            }

            /**
             * Filter out messages that already exist in our local list to avoid duplicates.
             * Use message ID for deduplication for better accuracy.
             */
            val existingMessageIds = _chatMessages.value.mapNotNull { it.id }.toSet()
            val newMessages = updatedNews.filter { message ->
                val isNew = message.id !in existingMessageIds
                if (!isNew) {
                    Timber.tag("ChatViewModel")
                        .d("fetchNewMessage: filtering out duplicate message with ID: ${message.id}")
                }
                isNew
            }
            Timber.tag("ChatViewModel")
                .d("fetchNewMessage: existing IDs count: ${existingMessageIds.size}, new messages count: ${newMessages.size}, total fetched: ${updatedNews.size}")

            // Insert only new messages to database (avoid duplicates)
            if (newMessages.isNotEmpty()) {
                chatRepository.insertMessages(newMessages)
            }

            _chatMessages.update {
                it.plus(newMessages).sortedBy { it.timestamp }
            }
            // update session in database
            chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = false)

            // Enhance message for ChatListViewModel update only
            val lastMessage = updatedNews.last()
            val enhancedLastMessage =
                if (!lastMessage.attachments.isNullOrEmpty() && lastMessage.content.isNullOrBlank()) {
                    if (lastMessage.authorId == appUser.mid) {
                        lastMessage.copy(content = "Attachment sent")
                    } else {
                        lastMessage.copy(content = "Attachment received")
                    }
                } else {
                    lastMessage
                }
            // update session in memory
            Timber.tag("ChatViewModel")
                .d("fetchNewMessage calling updateSession with message: ${enhancedLastMessage.content}, authorId: ${enhancedLastMessage.authorId}")
            chatListViewModel?.updateSession(enhancedLastMessage, hasNews = false)
        }
    }

    /**
     * Load the latest 10 messages from database
     */
    private suspend fun loadLatestMessages(receiptId: MimeiId): List<ChatMessage> {
        // Get session ID for this conversation
        val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

        val messages = chatRepository.loadMessagesBySession(sessionId, MESSAGES_PER_PAGE)
        // update session flag to false, means it is read.
        chatSessionRepository.updateExistingChatSession(appUser.mid, receiptId, hasNews = false)
        return messages.map { it.toChatMessage() }
    }

    /**
     * Load older messages for pull-to-refresh functionality
     */
    suspend fun loadOlderMessages() {
        if (_isLoadingOlderMessages.value || !_hasMoreMessages.value) {
            return
        }

        _isLoadingOlderMessages.value = true

        try {
            val currentMessages = _chatMessages.value
            if (currentMessages.isEmpty()) {
                _hasMoreMessages.value = false
                return
            }

            // Get the oldest message timestamp
            val oldestTimestamp = currentMessages.minOfOrNull { it.timestamp } ?: 0L
            
            // Get session ID for this conversation
            val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

            val olderMessages = chatRepository.loadOlderMessagesBySession(sessionId, oldestTimestamp, MESSAGES_PER_PAGE)
            
            if (olderMessages.size < MESSAGES_PER_PAGE) {
                _hasMoreMessages.value = false
            }

            if (olderMessages.isNotEmpty()) {
                val newMessages = olderMessages.map { it.toChatMessage() }
                _chatMessages.update { currentMessages ->
                    (newMessages + currentMessages).sortedBy { it.timestamp }
                }
            }
        } catch (e: Exception) {
            Timber.tag("ChatViewModel").e(e, "Error loading older messages")
            _toastMessage.value = "Failed to load older messages: ${e.message}"
        } finally {
            _isLoadingOlderMessages.value = false
        }
    }

    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
    }
}