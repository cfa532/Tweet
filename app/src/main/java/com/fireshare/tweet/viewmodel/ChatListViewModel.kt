package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatSessionRepository
import com.fireshare.tweet.chat.mergeMessagesWithSessions
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.toChatMessage
import com.fireshare.tweet.datamodel.toChatSession
import com.fireshare.tweet.datamodel.toEntity
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
            _chatSessions.update { loadChatSessions() }
        }
    }

    private suspend fun loadChatSessions(): List<ChatSession> {
        return chatSessionRepository.getAllSessions()
    }

    fun loadNewMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val newMessages = HproseInstance.previewNewMessages() ?: return@launch
            val updatedSessions = mergeMessagesWithSessions(_chatSessions.value, newMessages)
            // Do not update session database, only show new sessions on UI
            // Update session database only when user opens chat screen.
            _chatSessions.update { updatedSessions }
        }
    }

    fun getSender(userId: MimeiId) {
        viewModelScope.launch {
            val user = withContext(Dispatchers.IO) {
                HproseInstance.getUserBase(userId)
            }
            _userMap.value = _userMap.value.toMutableMap().apply { put(userId, user) }
        }
    }
}