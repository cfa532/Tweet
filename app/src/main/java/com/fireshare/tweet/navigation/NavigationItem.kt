package com.fireshare.tweet.navigation

import com.fireshare.tweet.datamodel.MimeiId
import kotlinx.serialization.Serializable

@Serializable
object NavRoot

@Serializable
data class ComposeComment(val tweetId: MimeiId)

@Serializable
object ProfileEditor

@Serializable
data class UserProfile(val userId: MimeiId)     // profile detail of a user

@Serializable
data class MediaPreview(val mid: MimeiId)   // preview media file by Mimei ID

@Serializable
sealed class NavigationItem {
    @Serializable
    data object TweetFeed: NavigationItem()
    @Serializable
    data class TweetDetail(val tweetId: MimeiId): NavigationItem()
    @Serializable
    data object ComposeTweet: NavigationItem()
    @Serializable
    data class MessageBox(val userId: MimeiId? = null): NavigationItem()
}