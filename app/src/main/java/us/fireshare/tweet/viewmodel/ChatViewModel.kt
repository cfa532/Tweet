package us.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.chat.ChatRepository
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.toChatMessage

@HiltViewModel( assistedFactory = ChatViewModel.ChatViewModelFactory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted val receiptId: MimeiId,
    private val chatRepository: ChatRepository,
    private val chatSessionRepository: ChatSessionRepository
    ): ViewModel()
{
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> get() = _chatMessages.asStateFlow()

    private val _receipt = MutableStateFlow<User>(User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl))
    val receipt: StateFlow<User> get() = _receipt.asStateFlow()

    var message = mutableStateOf("")
    var chatListViewModel: ChatListViewModel? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _receipt.value = HproseInstance.getUser(receiptId)
                ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)

            // get messages stored at local database
            _chatMessages.value = loadChatMessages(receiptId)
                .sortedBy { it.timestamp }

            // get unread messages from network
            fetchNewMessage()
        }
    }

    suspend fun sendMessage(content: String) {
        val message = ChatMessage(
            receiptId = receiptId,
            authorId = appUser.mid,
            timestamp = System.currentTimeMillis(),
            content = content.trim(),
            sessionId = null // Will be set when session is created/updated
        )
        // update message list in memory
        _chatMessages.value += message
        chatRepository.insertMessage(message)       // update chat records in Room
        HproseInstance.sendMessage(receiptId, message)  // send it out on network
        chatSessionRepository.updateChatSession(    // update session in Room
            appUser.mid,
            receiptId,
            hasNews = false
        )
        chatListViewModel?.updateSession(message, hasNews = false)  // update session list in memory
    }

    /**
     * Check new messages on writable host of an user.
     * */
    suspend fun fetchNewMessage(numOfMsgs: Int = 500) {
        val fetchedMessages = HproseInstance.fetchMessages(receiptId) ?: return
        val news = fetchedMessages.toMutableList()
        if (news.isNotEmpty()) {
            // Update timestamps with local system time for received messages
            val currentTime = System.currentTimeMillis()
            val updatedNews = news.map { message ->
                if (message.authorId != appUser.mid) {
                    // Update timestamp for incoming messages with local time
                    message.copy(timestamp = currentTime)
                } else {
                    message
                }
            }
            
            chatRepository.insertMessages(updatedNews.filter { it.authorId != appUser.mid })
            /**
             * All outgoing and incoming messages are stored at user's mimei database.
             * When fetching new messages, all messages during the last waiting period
             * are read. Have to filter out messages sent by appUser, which have been
             * inserted into Room database when sending out.
             * */
            _chatMessages.update {
                it.plus(updatedNews.filter { m ->
                    m.authorId != appUser.mid   // only count incoming messages
                })
            }
            // update session in database
            chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = false)
            // update session in memory
            chatListViewModel?.updateSession(updatedNews.last(), hasNews = false)
        }
    }

    private suspend fun loadChatMessages(receiptId: MimeiId): List<ChatMessage> {
        val messages = chatRepository.loadMessages(appUser.mid, receiptId, 50)
        // update session flag to false, means it is read.
        chatSessionRepository.updateChatSession(appUser.mid, receiptId, hasNews = false)
        return messages.map { it.toChatMessage() }
    }

    @AssistedFactory
    interface ChatViewModelFactory {
        fun create(receiptId: MimeiId): ChatViewModel
    }
}