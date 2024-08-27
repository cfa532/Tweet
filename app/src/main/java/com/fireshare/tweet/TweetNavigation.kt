package com.fireshare.tweet

import com.fireshare.tweet.datamodel.MimeiId
import kotlinx.serialization.Serializable

@Serializable
object NavRoot

@Serializable
object TweetFeed

@Serializable
object MessageList      // all messages of app user

@Serializable
data class MessageDetail(val userId: MimeiId)   // all messages with another user

@Serializable
object ComposeTweet

@Serializable
data class ComposeComment(val tweetId: MimeiId)

@Serializable
object ProfileEditor

// if commentId is not null, reading comment detail of current Tweet.
// the comment itself is a tweet too.
@Serializable
data class TweetDetail(
    val tweetId: String,
    val commentId: String?,
    )

@Serializable
data class UserProfile(val userId: MimeiId)     // profile detail of a user

@Serializable
data class MediaPreview(val mid: MimeiId)   // preview media file by Mimei ID
