package com.fireshare.tweet.datamodel

import androidx.test.services.events.TimeStamp
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage (
    val id: Long = System.currentTimeMillis(),      // used as key
    val authorId: MimeiId,      // author of the message
    val content: String? = null,
    val attachment: MimeiId? = null,
)

@Serializable
data class ChatSession (
    val userId: MimeiId,
    var lastMessage: ChatMessage,
)