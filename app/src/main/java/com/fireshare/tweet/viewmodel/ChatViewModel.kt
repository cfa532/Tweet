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
import com.google.gson.Gson
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
    private val _receipt = MutableStateFlow<User?>(null)
    val receipt: StateFlow<User?> get() = _receipt.asStateFlow()
    var textState = mutableStateOf("")

    init {

        viewModelScope.launch(Dispatchers.IO) {
            _receipt.value = HproseInstance.getUserBase(receiptId)

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
            content = textState.value.trim()
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
            val gson = Gson()
            val fetchedMessages = HproseInstance.fetchMessages(receiptId, numOfMsgs) ?: return@launch
            val news = mutableListOf<ChatMessage>()
            for(i in fetchedMessages.indices){
                val str = gson.toJson(fetchedMessages[i])
                news.add(gson.fromJson(str, ChatMessage::class.java))
            }
            if (news.isNotEmpty()) {
                repository.insertMessages(news)
                /**
                 * All outgoing and incoming messages are stored at user's mimei database.
                 * When fetching new messages, all messages during the last waiting period
                 * are read. Have to filter out messages sent by appUser, which have benn
                 * inserted into local database when sending out.
                 * */
                _chatMessages.update { it.plus(news.filter { m ->
                    m.authorId != appUser.mid
                }) }
                chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = true)
            }
        }
    }

    private suspend fun loadChatMessages(receiptId: MimeiId): List<ChatMessage> {
        val messages = repository.loadMessages(appUser.mid, receiptId, 50)
        return messages.map { it.toChatMessage() }
    }

    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
    }
}