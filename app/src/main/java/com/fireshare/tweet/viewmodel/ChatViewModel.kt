package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel( assistedFactory = ChatViewModel.ChatViewModelFactory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val receiptId: MimeiId ): ViewModel()
{
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages.asStateFlow()

    var receipt: User? = null
    var textState = mutableStateOf("")

    init {
        viewModelScope.launch {
            HproseInstance.getUserBase(receiptId)?.let { receipt = it }
            // load past 100 chat messages with the receipt
        }
    }
    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
    }

    fun sendMessage() {
        // add message to its own MM db and that of the receipt
        val message = ChatMessage(
            authorId = appUser.mid,
            content = textState.value
        )
        // For now, just add it to the local list
        _chatMessages.value += message
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.sendMessage(receiptId, message)
        }
    }

    fun fetchMessage() {
        // read message from server proactively.
    }
}