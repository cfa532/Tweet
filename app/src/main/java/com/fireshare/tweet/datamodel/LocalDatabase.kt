package com.fireshare.tweet

import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId

class LocalDatabase {
    private val messageStore = mutableMapOf<MimeiId, MutableList<ChatMessage>>()

    fun saveMessage(receiptId: MimeiId, message: ChatMessage) {
        val messages = messageStore.getOrPut(receiptId) { mutableListOf() }
        messages.add(0, message) // Add message to the beginning of the list
    }

    fun loadMessages(receiptId: MimeiId, limit: Int): List<ChatMessage> {
        return messageStore[receiptId]?.take(limit) ?: emptyList()
    }
}