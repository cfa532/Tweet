package us.fireshare.tweet.datamodel

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import us.fireshare.tweet.HproseInstance

@Serializable
@Entity(tableName = "tweets")
data class Tweet(
    @PrimaryKey var mid: MimeiId,
    val authorId: MimeiId,        // mid of the author, is also the mimei database Id
    var content: String? = null,   // content or attachments must have one.
    var timestamp: Long = System.currentTimeMillis(),
    var title: String? = null,

    var originalTweetId: MimeiId? = null, // this is retweet id of the original tweet
    var originalAuthorId: MimeiId? = null,  // authorId of the forwarded tweet

    // the following six attributes are for display only. Not stored in database.
    var author: User? = null,
    var originalTweet: Tweet? = null,        // the original tweet for display only.

    // if the current user has liked or bookmarked this tweet
    var favorites: MutableList<Boolean>? = mutableListOf(false, false, false),

    var favoriteCount: Int = 0,     // Number of likes

    var bookmarkCount: Int = 0, // Number of bookmarks

    // retweets ID, without comments.
    var retweetCount: Int = 0,  // Number of retweets

    // comments (tweets) Id on this tweet.
    var commentCount: Int = 0,  // Number of comments

    // List of media IDs attached to the tweet
    var attachments: List<MimeiFileType>? = null,
    var isPrivate: Boolean = false,     // Viewable by the author only, if true.
    val downloadable: Boolean? = false,  // only used in web version.
)

@Parcelize
@Serializable
data class User(
    var baseUrl: String? = null,
    var writableUrl: String? = null,
    var mid: MimeiId,  // Ensure MimeiId is Parcelable
    var name: String? = null,
    var username: String? = null,
    var password: String? = null,
    var avatar: MimeiId? = null,  // Ensure MimeiId is Parcelable
    var email: String? = null,
    var profile: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var lastLogin: Long? = System.currentTimeMillis(),
    var cloudDrivePort: Int? = 8964,

    var tweetCount: Int = 0,
    var followingCount: Int? = null,
    var followersCount: Int? = null,
    var bookmarksCount: Int? = null,
    var favoritesCount: Int? = null,
    var commentsCount: Int? = null,

    var hostIds: List<MimeiId>? = null,
    var publicKey: String? = null,

    var fansList: List<MimeiId>? = null,
    var followingList: List<MimeiId>? = null,
    var bookmarkedTweets: List<MimeiId>? = null,
    var favoriteTweets: List<MimeiId>? = null,
    var repliedTweets: List<MimeiId>? = null,
    var commentsList: List<MimeiId>? = null,
    var topTweets: List<MimeiId>? = null    // pinned tweets by the user.

) : Parcelable

/**
 * IP address of the first node in HostIds, which the user is authorized to write data on.
 * */
suspend fun User.writableUrl(): String? {
    return if (!writableUrl.isNullOrEmpty()) { // Check for null or empty string
//        this.baseUrl = writableUrl
        writableUrl
    } else {
        hostIds?.firstOrNull()?.let { hostId ->
            HproseInstance.getHostIP(hostId)?.let { hostIP ->
                "http://$hostIP".also { newUrl ->
                    this.writableUrl = newUrl
//                    this.baseUrl = newUrl
                }
            } ?: baseUrl
        }
    }
}

fun User.isGuest(): Boolean {
    return mid == TW_CONST.GUEST_ID
}

@Serializable
enum class MediaType {
    Image, Video, Audio, PDF, Word, Excel, PPT, Zip, Txt, Html, Unknown
}

@Serializable
// url is in the format of http://ip/mm/mimei_id
data class MediaItem(val url: String, var type: MediaType? = MediaType.Unknown)

fun String.getMimeiKeyFromUrl(): String {
    return this.substringAfterLast('/')
}

typealias MimeiId = String      // 27 or 64 character long string

// some application wise constants
object TW_CONST {
    const val GUEST_ID = "000000000000000000000000000"      // 27
    const val CHUNK_SIZE = 1024 * 1024 * 1      // 1MB in bytes
}

object UserActions {
    const val FAVORITE = 0
    const val BOOKMARK = 1
    const val RETWEET = 2
}
enum class UserContentType {
    FAVORITES,
    BOOKMARKS,
    COMMENTS
}

/**
 * When tweet is added or removed from the tweet feed list,
 * update tweet list in ProfileScreen, also add or remove it.
 * */
interface TweetActionListener {
    fun onTweetAdded(tweet: Tweet)
    fun onTweetDeleted(tweetId: MimeiId)
}

@Serializable
data class MimeiFileType(
    val mid: MimeiId,
    var type: MediaType,
    val size: Long? = null,
    val fileName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val aspectRatio: Float? = null,   // only used for video
    var url: String? = null,    // will not be persisted.
)