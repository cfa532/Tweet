package com.fireshare.tweet.chat

import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatMessageEntity
import com.fireshare.tweet.datamodel.ChatMessageDao
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.MimeiId

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

fun mergeMessagesWithSessions(
    existingSessions: List<ChatSession>,
    newMessages: List<ChatMessage>
): List<ChatSession> {
    val messageMap = mutableMapOf<Pair<MimeiId, MimeiId>, ChatMessage>()

    // Add existing messages to the map
    existingSessions.forEach { session ->
        val message = session.lastMessage
        val key = if (message.receiptId < message.authorId) {
            Pair(message.receiptId, message.authorId)
        } else {
            Pair(message.authorId, message.receiptId)
        }
        messageMap[key] = message
    }

    // Normalize the new messages and merge them into the map
    for(msg in newMessages) {

//    }
//    newMessages.forEach { msg ->
        println(msg)
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

    // Create a list of ChatSession from the merged messages
    return messageMap.values.map { lastMessage ->
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
}