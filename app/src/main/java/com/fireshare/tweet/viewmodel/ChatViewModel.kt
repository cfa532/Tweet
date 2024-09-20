package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatRepository
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatSessionEntity
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.toChatMessage
import com.fireshare.tweet.datamodel.toEntity
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
    private val database: ChatDatabase
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
    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
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
            repository.insertMessage(message.toEntity())
            HproseInstance.sendMessage(receiptId, message)

            // Update the ChatSession with the last new message
            updateSessionDatabase(message)
        }
    }

    fun fetchNewMessage(numOfMsgs: Int = 50) {
        viewModelScope.launch {
            val fetchedMessages = HproseInstance.fetchMessages(receiptId, numOfMsgs)
            fetchedMessages?.let{news ->
                _chatMessages.update { news.plus(it) }
                // update chat session
                updateSessionDatabase(news[news.size-1])
            }
        }
    }

    private suspend fun updateSessionDatabase(message: ChatMessage) {
        val sessionEntity = database.chatSessionDao().getSession(appUser.mid, receiptId)
        if (sessionEntity != null) {
            database.chatSessionDao().updateSession(appUser.mid, receiptId, message.timestamp,
                message.timestamp, false)
        } else {
            val newSession = ChatSessionEntity(
                timestamp = message.timestamp,
                userId = message.authorId,
                receiptId = message.receiptId,
                hasNews = false,
                lastMessageId = message.timestamp
            )
            database.chatSessionDao().insertSession(newSession)
        }
    }

    private suspend fun loadChatMessages(receiptId: MimeiId): List<ChatMessage> {
        val messages = repository.loadMessages(appUser.mid, receiptId, 50)
        return messages.map { it.toChatMessage() }
    }


    suspend fun getSender(userId: MimeiId): User? {
        return HproseInstance.getUserBase(userId)
    }
}