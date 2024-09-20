package com.fireshare.tweet.chat

import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.ChatMessageEntity
import com.fireshare.tweet.datamodel.ChatMessageDao
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.datamodel.MimeiId
import com.google.gson.Gson

class ChatRepository(private val chatMessageDao: ChatMessageDao) {
    suspend fun loadMessages(userId: String, receiptId: String, limit: Int): List<ChatMessageEntity> {
        return chatMessageDao.loadMessages(userId, receiptId, limit)
    }

    suspend fun insertMessage(message: ChatMessageEntity) {
        chatMessageDao.insertMessage(message)
    }
}

/**
 * Update existing chat sessions with incoming chat message.
 * Chat message is identified by its pair of authorId and receiptId. Depending on
 * the direction of message flow, the pair change switch. Whereas the session's
 * author is always the current app user, and its receipt is the one engaging in
 * conversation with the appUser.
 * */
fun mergeMessagesWithSessions(
    existingSessions: List<ChatSession>,
    newMessages: List<ChatMessage>
): List<ChatSession> {
    val messageMap = mutableMapOf<Pair<MimeiId, MimeiId>, ChatMessage>()
    fun normalizedKey(message: ChatMessage): Pair<MimeiId, MimeiId> {
        return if (message.receiptId < message.authorId) {
            Pair(message.receiptId, message.authorId)
        } else {
            Pair(message.authorId, message.receiptId)
        }
    }
    // Add existing messages to the map
    existingSessions.forEach { session ->
        val message = session.lastMessage
        val key = normalizedKey(message)
        messageMap[key] = message
    }

    // Normalize the new messages and merge them into the map
    val gson = Gson()
    for (i in newMessages.indices) {
        val json = gson.toJson(newMessages[i])
        val msg = gson.fromJson(json, ChatMessage::class.java)
        val key = normalizedKey(msg)
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

        // if the session receipt is author of any new message
        val hasNews = hasNews(newMessages, receiptId)
        ChatSession(
            timestamp = lastMessage.timestamp,
            userId = appUser.mid,
            receiptId = receiptId,
            hasNews = hasNews,
            lastMessage = lastMessage
        )
    }
}

fun hasNews(newMessages: List<ChatMessage>, receiptId: MimeiId): Boolean {
    val gson = Gson()
    for (i in newMessages.indices) {
        val json = gson.toJson(newMessages[i])
        val msg = gson.fromJson(json, ChatMessage::class.java)
        if (msg.authorId == receiptId)
            return true
    }
    return false
}