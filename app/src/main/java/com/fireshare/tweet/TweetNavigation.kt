package com.fireshare.tweet

import com.fireshare.tweet.datamodel.MimeiId
import kotlinx.serialization.Serializable

@Serializable
object TweetFeed

@Serializable
object MessageList      // all messages of app user

@Serializable
data class MessageDetail(val userId: MimeiId)   // all messages with another user

@Serializable
data class ComposeComment(val tweetId: MimeiId? = null)

@Serializable
object ComposeTweet

@Serializable
object ProfileEditor

@Serializable
data class TweetDetail(val tweetId: MimeiId)

@Serializable
data class UserProfile(val userId: MimeiId?)     // profile detail of a user

@Serializable
data class MediaPreview(val mid: MimeiId)   // preview media file by Mimei ID
