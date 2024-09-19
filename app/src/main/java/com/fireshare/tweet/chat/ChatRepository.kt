package com.fireshare.tweet.chat

import com.fireshare.tweet.datamodel.ChatMessageEntity
import com.fireshare.tweet.datamodel.ChatMessageDao

class ChatRepository(private val chatMessageDao: ChatMessageDao) {

    suspend fun insertMessage(message: ChatMessageEntity) {
        chatMessageDao.insertMessage(message)
    }

    suspend fun loadMessages(receiptId: String, limit: Int): List<ChatMessageEntity> {
        return chatMessageDao.loadMessages(receiptId, limit)
    }

    suspend fun loadMostRecentMessages(): List<ChatMessageEntity> {
        return chatMessageDao.loadMostRecentMessages()
    }
}