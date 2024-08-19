package com.fireshare.tweet

import androidx.navigation.NavHostController
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import kotlinx.serialization.Serializable

@Serializable
object DestTweetFeed

@Serializable
object DestMessageList      // all messages of app user

@Serializable
data class DestMessageDetail(val userId: MimeiId)   // all messages with another user

@Serializable
object DestComposeTweet

@Serializable
object DestProfileEditor

@Serializable
data class DestTweetDetail(val tweetId: MimeiId)

@Serializable
data class DestUserProfile(val user: User)     // profile detail of a user

@Serializable
data class DestMediaPreview(val mid: MimeiId)   // preview media file by Mimei ID
