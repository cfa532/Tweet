package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.chat.ChatRepository
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.toChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    private val _chatSessions = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatSessions: StateFlow<List<ChatMessage>> get() = _chatSessions.asStateFlow()

    init {
        viewModelScope.launch {
            _chatSessions.value = loadChatSessions() ?: emptyList()
        }
    }

    private suspend fun loadChatSessions(): List<ChatMessage>? {
        // Load last of unread message from senders
        val recentMessages = repository.loadMostRecentMessages()
        return recentMessages.map { it.toChatMessage() }
    }

    suspend fun getSender(userId: MimeiId): User? {
        return HproseInstance.getUserBase(userId)
    }
}