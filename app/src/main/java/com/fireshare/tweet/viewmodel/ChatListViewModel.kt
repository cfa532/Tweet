package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(): ViewModel()
{
    private val _chatSessions = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatSessions.asStateFlow()

    init {
        _chatSessions.value = loadChatSessions()
    }

    private fun loadChatSessions(): List<ChatMessage> {
        return HproseInstance.loadMostRecentMessages()
    }

    suspend fun getSender(userId: MimeiId): User? {
        return HproseInstance.getUserBase(userId)
    }
}
