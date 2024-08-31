package com.fireshare.tweet

import android.os.Bundle
import androidx.navigation.NavType
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
data class TweetDetail(val tweetId: MimeiId)

@Serializable
data class UserProfile(val userId: MimeiId)     // profile detail of a user

@Serializable
data class MediaPreview(val mid: MimeiId)   // preview media file by Mimei ID
