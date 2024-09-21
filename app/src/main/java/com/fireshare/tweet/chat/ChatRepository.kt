package com.fireshare.tweet.chat

import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatMessageEntity
import com.fireshare.tweet.datamodel.ChatMessageDao
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.toEntity
import com.google.gson.Gson

class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    suspend fun loadMessages(userId: String, receiptId: String, limit: Int): List<ChatMessageEntity> {
        return chatMessageDao.loadMessages(userId, receiptId, limit)
    }

    suspend fun insertMessage(message: ChatMessage) {
        chatMessageDao.insertMessage(message.toEntity())
    }

    suspend fun insertMessages(messages: List<ChatMessage>) {
        val messageEntities = messages.map { it.toEntity() }
        chatMessageDao.insertMessages(messageEntities)
    }
}
