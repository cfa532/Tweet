package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatRepository
import com.fireshare.tweet.chat.ChatSessionRepository
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.toChatMessage
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

@HiltViewModel( assistedFactory = ChatViewModel.ChatViewModelFactory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val receiptId: MimeiId,
    private val repository: ChatRepository,
    private val chatSessionRepository: ChatSessionRepository
    ): ViewModel()
{
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages.asStateFlow()

    var receipt = MutableStateFlow<User?>(null)
    var textState = mutableStateOf("")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            receipt.value = getSender(receiptId)
            // get messages stored at local database
            _chatMessages.value = loadChatMessages(receiptId).sortedBy { it.timestamp }

            // get unread messages from network
            fetchNewMessage()
        }
    }

    fun sendMessage() {
        val message = ChatMessage(
            receiptId = receiptId,
            authorId = appUser.mid,
            timestamp = System.currentTimeMillis(),
            content = textState.value
        )
        _chatMessages.value += message
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertMessage(message)
            HproseInstance.sendMessage(receiptId, message)
            chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = false)
        }
    }

    fun fetchNewMessage(numOfMsgs: Int = 500) {
        viewModelScope.launch(Dispatchers.IO) {
            val fetchedMessages = HproseInstance.fetchMessages(receiptId, numOfMsgs)
            fetchedMessages?.let { news ->
                if (news.isNotEmpty()) {
                    repository.insertMessages(news)
                    _chatMessages.update { news.plus(it) }
                    chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = true)
                }
            }
        }
    }

    private suspend fun loadChatMessages(receiptId: MimeiId): List<ChatMessage> {
        val messages = repository.loadMessages(appUser.mid, receiptId, 50)
        return messages.map { it.toChatMessage() }
    }

    suspend fun getSender(userId: MimeiId): User? {
        return HproseInstance.getUserBase(userId)
    }

    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
    }
}