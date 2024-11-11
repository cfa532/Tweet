package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatSessionRepository
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.concurrent.timer

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatSessionRepository: ChatSessionRepository
) : ViewModel() {

    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> get() = _chatSessions.asStateFlow()

    private val _userMap = MutableStateFlow<Map<MimeiId, User?>>(emptyMap())
    val userMap: StateFlow<Map<MimeiId, User?>> get() = _userMap.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            chatSessionRepository.getAllSessions().forEach { chatSession ->
                val user = HproseInstance.getUserBase(chatSession.receiptId)
                _userMap.update { it + (chatSession.receiptId to user) }
                _chatSessions.update { it + chatSession }
            }
        }
    }

    /**
     * Called only from ChatBox screen.
     * Given a message, update corresponding chat session.
     * If a session id is given, update its new message flag to false.
     * */
    fun updateSession(msg: ChatMessage?, sessionId: MimeiId? = null) {
        if (sessionId != null) {
            // update chat session's new message flag to false
            val session = _chatSessions.value.find { it.receiptId == sessionId }
            if (session != null) {
                _chatSessions.update { it - session }
                _chatSessions.update { it + session.copy(hasNews = false) }
            }
            return
        }
        if (msg == null) return
        val receiptId = if (msg.authorId==appUser.mid) msg.receiptId else msg.authorId
        chatSessions.value.find { it.receiptId == receiptId }?.let {
            val updatedSessions = it.copy(hasNews = false, lastMessage = msg, timestamp = msg.timestamp)
            _chatSessions.update { sessions -> sessions - it }
            _chatSessions.update { sessions -> sessions + updatedSessions }
        }
    }

    fun previewMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val newMessages = HproseInstance.checkNewMessages() ?: return@launch
            val updatedSessions =
                chatSessionRepository.mergeMessagesWithSessions(chatSessions.value, newMessages)

            // Do not update chat session database, only show new sessions on UI
            // Update chat session database only when user opens chat screen.
            updatedSessions.forEach { chatSession ->
                val existingSession =
                    _chatSessions.value.find { it.receiptId == chatSession.receiptId }
                if (existingSession != null) {
                    val updatedSession = existingSession.copy(
                        hasNews = chatSession.hasNews,
                        lastMessage = chatSession.lastMessage,
                        timestamp = chatSession.timestamp
                    )
                    _chatSessions.update { it - existingSession }
                    _chatSessions.update { it + updatedSession }
                } else {
                    _chatSessions.update { it + chatSession }
                }
            }
        }
    }

    suspend fun updateUser(userId: MimeiId) {
        val user = HproseInstance.getUserBase(userId) ?: return
        _userMap.update { it + (user.mid to user) }
    }
}