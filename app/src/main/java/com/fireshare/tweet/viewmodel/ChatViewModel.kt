package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableStateOf
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatViewModel( private val userId: MimeiId ) {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages.asStateFlow()

    var aite = mutableStateOf("")
    fun sendMessage(message: ChatMessage) {

    }
}