package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
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
import us.fireshare.tweet.R
import us.fireshare.tweet.chat.ChatRepository
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.TweetCacheManager
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

    // State to trigger scroll to bottom when current user sends a message
    private val _shouldScrollToBottom = MutableStateFlow(false)
    val shouldScrollToBottom: StateFlow<Boolean> get() = _shouldScrollToBottom.asStateFlow()

    companion object {
        private const val MESSAGES_PER_PAGE = 10  // Load only 10 most recent messages initially
        
        /**
         * Helper function to check if a message is new (not already in the list)
         */
        private fun isNewMessage(message: ChatMessage, existingMessages: List<ChatMessage>): Boolean {
            val existingIds = existingMessages.map { it.id }.toSet()
            return message.id !in existingIds
        }
    }

    init {
        // Load initial data
        viewModelScope.launch(Dispatchers.IO) {
            // OPTIMIZED: Load cached user data FIRST to avoid blocking screen
            val cachedUser = TweetCacheManager.getCachedUser(receiptId)
            if (cachedUser != null && !cachedUser.isGuest()) {
                _receipt.value = cachedUser
                Timber.tag("ChatViewModel").d("Loaded cached user for chat: ${cachedUser.username}")
            } else {
                // No cached user, use guest placeholder temporarily
                _receipt.value = User(mid = receiptId, username = "loading...", baseUrl = appUser.baseUrl)
                Timber.tag("ChatViewModel").d("No cached user found, using placeholder")
            }
            
            // Load messages from database (fast)
            _chatMessages.value = loadLatestMessages(receiptId)
                .sortedBy { it.timestamp }
            Timber.tag("ChatViewModel").d("Loaded ${_chatMessages.value.size} messages from database")
            
            // Fetch fresh user data from network in background (slow)
            // This runs asynchronously and doesn't block the screen from appearing
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val freshUser = HproseInstance.fetchUser(receiptId)
                    if (freshUser != null && !freshUser.isGuest()) {
                        _receipt.value = freshUser
                        Timber.tag("ChatViewModel").d("Updated with fresh user data from network: ${freshUser.username}")
                    }
                } catch (e: Exception) {
                    Timber.tag("ChatViewModel").e(e, "Failed to fetch fresh user data, using cached")
                }
            }
        }
        
        // CRITICAL: Start SINGLE event listener for ALL background message uploads
        // This prevents multiple collectors from interfering with each other
        startListeningToMessageEvents()
    }
    
    /**
     * Start listening to message send events from background workers.
     * This should only be called ONCE in init block to prevent multiple collectors.
     */
    private fun startListeningToMessageEvents() {
        viewModelScope.launch {
            Timber.tag("ChatViewModel").d("Starting single event listener for receiptId: $receiptId")
            TweetNotificationCenter.events.collect { event ->
                when (event) {
                    is TweetEvent.ChatMessageSent -> {
                        if (event.message.receiptId == receiptId) {
                            Timber.tag("ChatViewModel").d("Received ChatMessageSent event: ${event.message.id}")
                            
                            // Check if this is replacing an optimistic message
                            val hasOptimisticMessage = _chatMessages.value.any { 
                                it.timestamp == event.message.timestamp && 
                                it.localAttachmentUri != null &&
                                it.attachments == null
                            }
                            
                            if (hasOptimisticMessage) {
                                // Replace optimistic message with real message
                                _chatMessages.update { messages ->
                                    messages.map { msg ->
                                        if (msg.timestamp == event.message.timestamp && 
                                            msg.localAttachmentUri != null &&
                                            msg.attachments == null) {
                                            // Replace with real message
                                            event.message
                                        } else {
                                            msg
                                        }
                                    }.sortedBy { it.timestamp }
                                }
                                Timber.tag("ChatViewModel").d("Replaced optimistic message with real message: ${event.message.id}")
                            } else if (isNewMessage(event.message, _chatMessages.value)) {
                                // Add the real message to UI only if it's new
                                _chatMessages.update { messages ->
                                    (messages + event.message).sortedBy { it.timestamp }
                                }
                                Timber.tag("ChatViewModel").d("Added new message: ${event.message.id}")
                            } else {
                                Timber.tag("ChatViewModel").d("Skipping duplicate message: ${event.message.id}")
                            }

                            // Insert message to database
                            chatRepository.insertMessage(event.message)
                            
                            // Trigger scroll to bottom for messages from current user
                            if (event.message.authorId == appUser.mid) {
                                _shouldScrollToBottom.value = true
                            }
                            
                            // Update chat session (always update, even if message already exists)
                            val previewMessage = chatSessionRepository.updateChatSessionWithMessage(
                                appUser.mid,
                                receiptId,
                                event.message,
                                hasNews = false
                            )
                            chatListViewModel?.updateSession(previewMessage, hasNews = false)
                        }
                    }

                    is TweetEvent.ChatMessageSendFailed -> {
                        Timber.tag("ChatViewModel").e("Received ChatMessageSendFailed: ${event.error}")
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
        val messageContent = content.trim().ifBlank { null }

        // Get or create session ID for this conversation
        val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)
        Timber.tag("ChatViewModel").d("sendTextMessage: Using sessionId=$sessionId for message, appUser=${appUser.mid}, receipt=$receiptId")

        val message = ChatMessage(
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
            Timber.tag("ChatViewModel").d("sendTextMessage: Inserting message to DB: id=${message.id}, sessionId=${message.sessionId}")
            chatRepository.insertMessage(message)
            
            // Update ChatSession immediately so ChatListView reflects the change
            val previewMessage = chatSessionRepository.updateChatSessionWithMessage(
                appUser.mid,
                receiptId,
                message,
                hasNews = false
            )
            chatListViewModel?.updateSession(previewMessage, hasNews = false)
            
            // Send message and get result
            val (success, errorMsg) = HproseInstance.sendMessage(receiptId, message)
            
            if (success) {
                // Message sent successfully, ensure session is updated (already updated above)
                Timber.tag("ChatViewModel")
                    .d("sendTextMessage message sent successfully: ${message.content}")
                
                // Trigger scroll to bottom
                _shouldScrollToBottom.value = true
            } else {
                // Message failed to send, update the message with failure status
                val failedMessage = message.copy(success = false, errorMsg = errorMsg)
                _chatMessages.update { messages ->
                    messages.map { if (it.id == message.id) failedMessage else it }
                }
                chatRepository.insertMessage(failedMessage)
            }
        } catch (e: Exception) {
            Timber.tag("ChatViewModel").e(e, "Error sending text message")
            // Update message with failure status
            val failedMessage = message.copy(success = false, errorMsg = e.message ?: "Network error")
            _chatMessages.update { messages ->
                messages.map { if (it.id == message.id) failedMessage else it }
            }
            chatRepository.insertMessage(failedMessage)
        }
    }

    private suspend fun sendMessageWithAttachment(
        content: String,
        attachmentUri: Uri,
        context: Context
    ) {
        // Show sending status toast
        _toastMessage.value = context.getString(R.string.uploading_attachment_background)

        // Get or create session ID for this conversation
        val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)
        
        val messageTimestamp = System.currentTimeMillis()

        // Create optimistic message with local Uri for immediate display
        val optimisticMessage = ChatMessage(
            receiptId = receiptId,
            authorId = appUser.mid,
            timestamp = messageTimestamp,
            content = content.trim().ifBlank { null },
            attachments = null, // Will be filled in when upload completes
            sessionId = sessionId,
            localAttachmentUri = attachmentUri.toString() // Store local Uri for immediate display
        )
        
        // Add optimistic message to UI immediately for instant feedback
        _chatMessages.update { messages ->
            (messages + optimisticMessage).sortedBy { it.timestamp }
        }
        
        // Trigger scroll to bottom
        _shouldScrollToBottom.value = true
        
        Timber.tag("ChatViewModel").d("Created optimistic message with local Uri: ${optimisticMessage.id}")

        // Create work request for background processing
        val workRequest = OneTimeWorkRequestBuilder<SendChatMessageWorker>()
            .setInputData(
                workDataOf(
                    "receiptId" to receiptId,
                    "content" to content,
                    "attachmentUri" to attachmentUri.toString(),
                    "sessionId" to sessionId,
                    "messageTimestamp" to messageTimestamp,
                    "optimisticMessageId" to optimisticMessage.id // Pass message ID to replace later
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L, // 10 seconds
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        // Enqueue the work
        WorkManager.getInstance(context).enqueue(workRequest)
        
        Timber.tag("ChatViewModel").d("Enqueued background message upload worker for receiptId: $receiptId")
        // Worker completion events will be handled by the single event listener in init block
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }
    
    fun resetScrollToBottomFlag() {
        _shouldScrollToBottom.value = false
    }

    /**
     * Check new messages on writable host of an user.
     * */
    suspend fun fetchNewMessage() {
        val fetchedMessages = HproseInstance.fetchMessages(receiptId) ?: return
        val news = fetchedMessages.toMutableList()
        Timber.tag("ChatViewModel").d("fetchNewMessage fetched ${news.size} messages $receiptId")
        if (news.isNotEmpty()) {
            // Get or create session ID for this conversation
            val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

            // Add session ID to messages
            val updatedNews = news.map { message ->
                message.copy(sessionId = sessionId)
            }

            /**
             * Deduplication logic using unique message IDs to prevent message duplication.
             * Check both in-memory list and database to avoid duplicates from previewMessages.
             */
            val newMessages = chatSessionRepository.filterExistingMessages(updatedNews).filter { message ->
                // Also check in-memory list to avoid adding messages already in UI
                val isNewInMemory = isNewMessage(message, _chatMessages.value)
                
                if (!isNewInMemory) {
                    Timber.tag("ChatViewModel")
                        .d("fetchNewMessage: filtering out duplicate message with ID: ${message.id} (already in memory)")
                }
                isNewInMemory
            }
            Timber.tag("ChatViewModel")
                .d("fetchNewMessage: existing messages count: ${_chatMessages.value.size}, new messages count: ${newMessages.size}, total fetched: ${updatedNews.size}")

            // Insert only new messages to database (avoid duplicates)
            if (newMessages.isNotEmpty()) {
                chatRepository.insertMessages(newMessages)
            }

            _chatMessages.update { existing ->
                (existing + newMessages).sortedBy { it.timestamp }
            }
            // update session in memory - only update if we have new messages
            // Don't call updateChatSessionWithMessage here as it will insert messages again
            // Messages are already inserted above, just update the session metadata
            if (newMessages.isNotEmpty()) {
                val lastMessage = newMessages.maxByOrNull { it.timestamp }
                lastMessage?.let { message ->
                    Timber.tag("ChatViewModel")
                        .d("fetchNewMessage updating session with message: ${message.content}, authorId: ${message.authorId}")
                    // Just update the session, don't insert message again (it's already inserted above)
                    // updateChatSession will find the latest message from the database and update the session
                    chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = false)
                    // Update session in memory - use the message directly (preview will be handled by ChatListViewModel if needed)
                    chatListViewModel?.updateSession(message, hasNews = false)
                }
            } else {
                // No new messages, just mark session as read
                chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = false)
            }
        }
    }

    /**
     * Load the latest 20 messages from database
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
     * Reload messages from database (public method for screen re-entry)
     */
    suspend fun reloadMessagesFromDatabase() {
        val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)
        Timber.tag("ChatViewModel").d("reloadMessagesFromDatabase: Loading from sessionId=$sessionId, appUser=${appUser.mid}, receipt=$receiptId")
        val messages = loadLatestMessages(receiptId).sortedBy { it.timestamp }
        _chatMessages.value = messages
        Timber.tag("ChatViewModel").d("reloadMessagesFromDatabase: Loaded ${messages.size} messages from database")
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

    /**
     * Resend a failed message
     */
    suspend fun resendMessage(message: ChatMessage) {
        Timber.tag("ChatViewModel").d("Attempting to resend message: ${message.id}")
        
        // Reset the message to sending state with updated timestamp
        val currentTime = System.currentTimeMillis()
        val resendingMessage = message.copy(
            success = true, 
            errorMsg = null,
            timestamp = currentTime
        )
        Timber.tag("ChatViewModel").d("Updated timestamp from ${message.timestamp} to $currentTime")
        
        _chatMessages.update { messages ->
            messages.map { if (it.id == message.id) resendingMessage else it }.sortedBy { it.timestamp }
        }
        chatRepository.insertMessage(resendingMessage)
        
        try {
            // Send message and get result
            val (success, errorMsg) = HproseInstance.sendMessage(receiptId, resendingMessage)
            
            if (success) {
                Timber.tag("ChatViewModel").d("Message resent successfully: ${message.content}")
                
                // Update ChatSession
                val previewMessage = chatSessionRepository.updateChatSessionWithMessage(
                    appUser.mid,
                    receiptId,
                    resendingMessage,
                    hasNews = false
                )
                chatListViewModel?.updateSession(previewMessage, hasNews = false)
                
                // Trigger scroll to bottom
                _shouldScrollToBottom.value = true
            } else {
                // Message failed to send again, update with failure status
                val failedMessage = resendingMessage.copy(success = false, errorMsg = errorMsg)
                _chatMessages.update { messages ->
                    messages.map { if (it.id == message.id) failedMessage else it }
                }
                chatRepository.insertMessage(failedMessage)
            }
        } catch (e: Exception) {
            Timber.tag("ChatViewModel").e(e, "Error resending message")
            // Update message with failure status
            val failedMessage = resendingMessage.copy(success = false, errorMsg = e.message ?: "Network error")
            _chatMessages.update { messages ->
                messages.map { if (it.id == message.id) failedMessage else it }
            }
            chatRepository.insertMessage(failedMessage)
        }
    }

    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
    }
}