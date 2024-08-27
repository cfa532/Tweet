package com.fireshare.tweet

import com.fireshare.tweet.datamodel.MimeiId
import kotlinx.serialization.Serializable

@Serializable
object TweetFeed

@Serializable
object MessageList      // all messages of app user

@Serializable
data class MessageDetail(var userId: MimeiId)   // all messages with another user

@Serializable
object ComposeTweet

@Serializable
data class ComposeComment(var tweetId: MimeiId)

@Serializable
object ProfileEditor

// if commentId is not null, reading comment detail of current Tweet.
// the comment itself is a tweet too.
@Serializable
data class TweetDetail(
    var tweetId: String,
    var commentId: String?,
    )

@Serializable
data class UserProfile(var userId: MimeiId)     // profile detail of a user

@Serializable
data class MediaPreview(var mid: MimeiId)   // preview media file by Mimei ID
