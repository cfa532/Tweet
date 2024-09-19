package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.toChatMessage
import com.fireshare.tweet.datamodel.toChatSession
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val database: ChatDatabase
) : ViewModel() {

    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> get() = _chatSessions.asStateFlow()

    init {
        viewModelScope.launch {
            _chatSessions.value = loadChatSessions() ?: emptyList()
        }
    }

    private suspend fun loadChatSessions(): List<ChatSession> {
        val sessionEntities = database.chatSessionDao().getAllSessions()
        return sessionEntities.mapNotNull { sessionEntity ->
            val lastMessageEntity = database.chatMessageDao().loadMessages(sessionEntity.receiptId, 1).firstOrNull()
            lastMessageEntity?.toChatMessage()?.let { lastMessage ->
                sessionEntity.toChatSession(lastMessage)
            }
        }
    }

    fun loadNewMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val newMessages = HproseInstance.previewNewMessages()

            val messageMap = mutableMapOf<Pair<MimeiId, MimeiId>, ChatMessage>()

            // Add existing messages to the map
            chatSessions.value.forEach { session ->
                val message = session.lastMessage
                val key = if (message.receiptId < message.authorId) {
                    Pair(message.receiptId, message.authorId)
                } else {
                    Pair(message.authorId, message.receiptId)
                }
                messageMap[key] = message
            }

            // Normalize the new messages and merge them into the map
            if (newMessages != null) {
                val gson = Gson()
                for (i in newMessages.indices) {
                    val str = gson.toJson(newMessages[i])
                    val msg: ChatMessage = gson.fromJson(str, ChatMessage::class.java)
                    val key = if (msg.receiptId < msg.authorId) {
                        Pair(msg.receiptId, msg.authorId)
                    } else {
                        Pair(msg.authorId, msg.receiptId)
                    }
                    val existingMessage = messageMap[key]
                    if (existingMessage == null || msg.timestamp > existingMessage.timestamp) {
                        messageMap[key] = msg
                    }
                }
            }

            // Create a list of ChatSession from the merged messages
            val updatedSessions = messageMap.values.map { lastMessage ->
                val receiptId = if (lastMessage.authorId == appUser.mid) {
                    lastMessage.receiptId
                } else lastMessage.authorId
                ChatSession(
                    timestamp = lastMessage.timestamp,
                    userId = appUser.mid,
                    receiptId = receiptId,
                    hasNews = true,
                    lastMessage = lastMessage
                )
            }

            _chatSessions.value = updatedSessions
        }
    }
}