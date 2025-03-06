package us.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.MimeiId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatSessionRepository: ChatSessionRepository
) : ViewModel() {
    // chat between pair of users
    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> get() = _chatSessions.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            chatSessionRepository.getAllSessions().forEach { chatSession ->
                _chatSessions.update { it + chatSession }
            }
        }
    }

    /**
     * Called only from ChatBox screen. Given a message, update corresponding chat session.
     * If a session id is given, update its hasNews message flag to false.
     * */
    fun updateSession(msg: ChatMessage?, hasNews: Boolean = false, sessionId: MimeiId? = null) {
        val targetReceiptId =
            sessionId ?: if (msg?.receiptId == appUser.mid) msg.authorId else msg?.receiptId

        val existingSession = _chatSessions.value.find { it.receiptId == targetReceiptId }

        if (existingSession != null) {
            val updatedSession = existingSession.copy(
                hasNews = if (sessionId != null) false else hasNews,
                lastMessage = msg ?: existingSession.lastMessage,
                timestamp = msg?.timestamp ?: existingSession.timestamp
            )
            _chatSessions.update { sessions ->
                (sessions - existingSession + updatedSession).sortedByDescending { it.timestamp }
            }
        } else if (msg != null) { // Only create a new session if a message is provided
            _chatSessions.update { sessions ->
                sessions + ChatSession(
                    userId = appUser.mid,
                    receiptId = msg.receiptId,
                    hasNews = hasNews,
                    lastMessage = msg
                )
            }
        }

        // Update 'hasNews' for the sender/receiver session if msg is not null
        if (msg != null) {
            val receiptIdForNewsUpdate =
                if (msg.authorId == appUser.mid) msg.receiptId else msg.authorId
            _chatSessions.value.find { it.receiptId == receiptIdForNewsUpdate }
                ?.let { sessionForNewsUpdate ->
                    val updatedSessionForNewsUpdate = sessionForNewsUpdate.copy(
                        hasNews = false,
                        lastMessage = msg,
                        timestamp = msg.timestamp
                    )
                    _chatSessions.update { sessions ->
                        (sessions - sessionForNewsUpdate + updatedSessionForNewsUpdate)
                            .sortedByDescending { it.timestamp } }
                }
        }
    }

    suspend fun previewMessages() {
        val newMessages = HproseInstance.checkNewMessages() ?: return
        val updatedSessions =
            chatSessionRepository.mergeMessagesWithSessions(chatSessions.value, newMessages)

        // Do not update chat session database, only show new sessions on UI
        // Update chat session database only when user opens chat screen.
        val existingSessionsMap = _chatSessions.value.associateBy { it.receiptId }
        val updatedChatSessions = _chatSessions.value.toMutableList()
        updatedSessions.forEach { chatSession ->
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
}