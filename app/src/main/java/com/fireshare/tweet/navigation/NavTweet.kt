package com.fireshare.tweet.navigation

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.widget.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
object NavTwee

@Serializable
data class ComposeComment(val tweetId: MimeiId)

@Serializable
object ProfileEditor

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

    @Serializable
    data class MediaViewer(val params: MediaViewerParams) : NavTweet

    @Serializable
    data class DeepLink(val tweetId: MimeiId, val authorId: MimeiId) : NavTweet
}

@Serializable
data class MediaViewerParams(
    val mediaItems: List<MediaItem>,
    val index: Int = 0,
    val tweetId: MimeiId
)

@Serializable
object TweetNavType {
    val MediaViewerType = object : NavType<MediaViewerParams>(
        isNullableAllowed = false
    ) {
        override fun get(bundle: Bundle, key: String): MediaViewerParams? {
            return Json.decodeFromString(bundle.getString(key) ?: return null)
        }

        override fun parseValue(value: String): MediaViewerParams {
            return Json.decodeFromString(Uri.decode(value))
        }

        override fun put(bundle: Bundle, key: String, value: MediaViewerParams) {
            bundle.putString(key, Json.encodeToString(value))
        }

        override fun serializeAsValue(value: MediaViewerParams): String {
            return Uri.encode(Json.encodeToString(value))
        }
    }
}