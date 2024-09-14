package com.fireshare.tweet.navigation

import com.fireshare.tweet.datamodel.MimeiId
import kotlinx.serialization.Serializable

@Serializable
object NavTwee

@Serializable
data class ComposeComment(val tweetId: MimeiId)

@Serializable
object ProfileEditor

@Serializable
data class MediaViewer(val midList: List<MimeiId>, val index: Int = 0)   // preview media file by Mimei ID

@Serializable
sealed interface NavTweet {
    @Serializable
    data object TweetFeed : NavTweet

    @Serializable
    data class TweetDetail(val tweetId: MimeiId) : NavTweet

    @Serializable
    data object ComposeTweet : NavTweet

    @Serializable
    data object ChatList: NavTweet

    @Serializable
    data class ChatBox(val receiptId: MimeiId) : NavTweet

    @Serializable
    data object Registration : NavTweet

    @Serializable
    data object Login : NavTweet

    @Serializable
    data class UserProfile(val userId: MimeiId) : NavTweet

    @Serializable
    data class Following(val userId: MimeiId) : NavTweet        // accounts the user is following

    @Serializable
    data class Follower(val userId: MimeiId) : NavTweet
}