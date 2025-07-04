package us.fireshare.tweet.datamodel

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.serialization.Serializable
import timber.log.Timber

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
    var downloadable: Boolean = false,  // only used in web version.
) {
    companion object {
        // Singleton dictionary to store tweet instances
        private val instances = mutableMapOf<String, Tweet>()
        private val instanceLock = Any()

        /**
         * Get or create a tweet instance with the given parameters
         */
        @Synchronized
        fun getInstance(
            mid: String,
            authorId: String,
            content: String? = null,
            timestamp: Long = System.currentTimeMillis(),
            title: String? = null,
            originalTweetId: String? = null,
            originalAuthorId: String? = null,
            author: User? = null,
            favorites: List<Boolean>? = listOf(false, false, false),
            favoriteCount: Int = 0,
            bookmarkCount: Int = 0,
            retweetCount: Int = 0,
            commentCount: Int = 0,
            attachments: List<MimeiFileType>? = null,
            isPrivate: Boolean = false,
            downloadable: Boolean = false
        ): Tweet {
            synchronized(instanceLock) {
                val existingInstance = instances[mid]
                if (existingInstance != null) {
                    // Update existing instance with new values
                    content?.let { existingInstance.content = it }
                    title?.let { existingInstance.title = it }
                    author?.let { existingInstance.author = it }
                    favorites?.let { existingInstance.favorites = it.toMutableList() }
                    existingInstance.favoriteCount = favoriteCount
                    existingInstance.bookmarkCount = bookmarkCount
                    existingInstance.retweetCount = retweetCount
                    existingInstance.commentCount = commentCount
                    attachments?.let { existingInstance.attachments = it }
                    existingInstance.isPrivate = isPrivate
                    existingInstance.downloadable = downloadable
                    return existingInstance
                }

                val newInstance = Tweet(
                    mid = mid,
                    authorId = authorId,
                    content = content,
                    timestamp = timestamp,
                    title = title,
                    originalTweetId = originalTweetId,
                    originalAuthorId = originalAuthorId,
                    author = author,
                    favorites = favorites?.toMutableList(),
                    favoriteCount = favoriteCount,
                    bookmarkCount = bookmarkCount,
                    retweetCount = retweetCount,
                    commentCount = commentCount,
                    attachments = attachments,
                    isPrivate = isPrivate,
                    downloadable = downloadable
                )
                instances[mid] = newInstance
                return newInstance
            }
        }

        /**
         * Clear a specific tweet instance
         */
        @Synchronized
        fun clearInstance(mid: String) {
            synchronized(instanceLock) {
                instances.remove(mid)
            }
        }

        /**
         * Clear all tweet instances
         */
        @Synchronized
        fun clearAllInstances() {
            synchronized(instanceLock) {
                instances.clear()
            }
        }

        /**
         * Creates a Tweet from a dictionary returned by the network call
         */
        fun from(dict: Map<String, Any>): Tweet {
            try {
                val gson = Gson()
                val jsonString = gson.toJson(dict)
                val tweet = gson.fromJson(jsonString, Tweet::class.java)
                
                return getInstance(
                    mid = tweet.mid,
                    authorId = tweet.authorId,
                    content = tweet.content,
                    timestamp = tweet.timestamp,
                    title = tweet.title,
                    originalTweetId = tweet.originalTweetId,
                    originalAuthorId = tweet.originalAuthorId,
                    author = tweet.author,
                    favorites = tweet.favorites,
                    favoriteCount = tweet.favoriteCount,
                    bookmarkCount = tweet.bookmarkCount,
                    retweetCount = tweet.retweetCount,
                    commentCount = tweet.commentCount,
                    attachments = tweet.attachments,
                    isPrivate = tweet.isPrivate,
                    downloadable = tweet.downloadable
                )
            } catch (e: Exception) {
                Timber.e("Error converting dictionary to Tweet: $e")
                throw RuntimeException("Cannot convert dictionary to Tweet", e)
            }
        }

        /**
         * Legacy decode method for backward compatibility
         */
        fun decode(jsonString: String): Tweet? {
            return try {
                val gson = com.google.gson.Gson()
                val jsonObject = gson.fromJson(jsonString, com.google.gson.JsonObject::class.java)

                // Convert timestamp to Long if it's a string
                if (jsonObject.has("timestamp")) {
                    val timestamp = jsonObject.get("timestamp")
                    if (timestamp.isJsonPrimitive && timestamp.asJsonPrimitive.isString) {
                        jsonObject.addProperty("timestamp", timestamp.asString.toLong())
                    }
                }

                // Handle attachments timestamps if present
                if (jsonObject.has("attachments")) {
                    val attachmentsArray = jsonObject.getAsJsonArray("attachments")
                    attachmentsArray?.forEach { attachment ->
                        if (attachment.isJsonObject) {
                            val attachmentObj = attachment.asJsonObject
                            if (attachmentObj.has("timestamp")) {
                                val timestamp = attachmentObj.get("timestamp")
                                if (timestamp.isJsonPrimitive && timestamp.asJsonPrimitive.isString) {
                                    attachmentObj.addProperty("timestamp", timestamp.asString.toLong())
                                }
                            }
                        }
                    }
                }

                gson.fromJson(jsonObject, Tweet::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    // Computed properties for user interaction states
    var isFavorite: Boolean
        get() = favorites?.getOrNull(0) ?: false
        set(value) {
            if (favorites == null) {
                favorites = mutableListOf(false, false, false)
            }
            favorites!![0] = value
        }

    var isBookmarked: Boolean
        get() = favorites?.getOrNull(1) ?: false
        set(value) {
            if (favorites == null) {
                favorites = mutableListOf(false, false, false)
            }
            favorites!![1] = value
        }

    var isRetweeted: Boolean
        get() = favorites?.getOrNull(2) ?: false
        set(value) {
            if (favorites == null) {
                favorites = mutableListOf(false, false, false)
            }
            favorites!![2] = value
        }

    /**
     * Updates the tweet instance with values from another tweet
     */
    fun update(from: Tweet) {
        // Update all properties except author
        from.content?.let { this.content = it }
        from.title?.let { this.title = it }
        from.favorites?.let { this.favorites = it.toMutableList() }
        this.favoriteCount = from.favoriteCount
        this.bookmarkCount = from.bookmarkCount
        this.retweetCount = from.retweetCount
        this.commentCount = from.commentCount
        from.attachments?.let { this.attachments = it }
        this.isPrivate = from.isPrivate
        this.downloadable = from.downloadable
        this.timestamp = from.timestamp
    }

    /**
     * Updates the tweet instance with values from a dictionary
     */
    fun update(dict: Map<String, Any>) {
        try {
            val gson = Gson()
            val jsonElement: JsonElement = gson.toJsonTree(dict)
            val tempTweet = gson.fromJson(jsonElement, Tweet::class.java)

            // Update this instance with the new values
            tempTweet.content?.let { this.content = it }
            tempTweet.title?.let { this.title = it }
            tempTweet.author?.let { this.author = it }
            tempTweet.favorites?.let { this.favorites = it.toMutableList() }
            this.favoriteCount = tempTweet.favoriteCount
            this.bookmarkCount = tempTweet.bookmarkCount
            this.retweetCount = tempTweet.retweetCount
            this.commentCount = tempTweet.commentCount
            tempTweet.attachments?.let { this.attachments = it }
            this.isPrivate = tempTweet.isPrivate
            this.downloadable = tempTweet.downloadable
            this.timestamp = tempTweet.timestamp
        } catch (e: Exception) {
            Timber.e("Error updating tweet from dictionary: $e")
            throw RuntimeException("Cannot update tweet from dictionary", e)
        }
    }

    /**
     * Creates a copy of the tweet with updated attributes
     */
    fun copy(
        content: String? = null,
        title: String? = null,
        author: User? = null,
        favorites: List<Boolean>? = null,
        favoriteCount: Int? = null,
        bookmarkCount: Int? = null,
        retweetCount: Int? = null,
        commentCount: Int? = null,
        attachments: List<MimeiFileType>? = null,
        isPrivate: Boolean? = null,
        downloadable: Boolean? = null
    ): Tweet {
        return getInstance(
            mid = this.mid,
            authorId = this.authorId,
            content = content ?: this.content,
            timestamp = this.timestamp,
            title = title ?: this.title,
            originalTweetId = this.originalTweetId,
            originalAuthorId = this.originalAuthorId,
            author = author ?: this.author,
            favorites = favorites ?: this.favorites,
            favoriteCount = favoriteCount ?: this.favoriteCount,
            bookmarkCount = bookmarkCount ?: this.bookmarkCount,
            retweetCount = retweetCount ?: this.retweetCount,
            commentCount = commentCount ?: this.commentCount,
            attachments = attachments ?: this.attachments,
            isPrivate = isPrivate ?: this.isPrivate,
            downloadable = downloadable ?: this.downloadable
        )
    }

    /**
     * Checks if this tweet is pinned based on a list of pinned tweets
     */
    fun isPinned(pinnedTweets: List<Map<String, Any>>): Boolean {
        return pinnedTweets.any { dict ->
            val pinnedTweet = dict["tweet"] as? Tweet
            pinnedTweet?.mid == this.mid
        }
    }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Tweet
        return mid == other.mid
    }

    override fun hashCode(): Int {
        return mid.hashCode()
    }
}



@Serializable
enum class MediaType {
    Image, Video, Audio, PDF, Word, Excel, PPT, Zip, Txt, Html, Unknown
}

/**
 * Extension function for merging tweets into an array
 */
fun MutableList<Tweet>.mergeTweets(newTweets: List<Tweet>) {
    Timber.d("[TweetListView] Merging ${newTweets.size} tweets")
    // Create a dictionary to track unique tweets by their mid
    val uniqueTweets = mutableMapOf<String, Tweet>()
    
    // Add existing tweets to dictionary
    for (tweet in this) {
        uniqueTweets[tweet.mid] = tweet
    }
    
    // Add new tweets, overwriting existing ones if they have the same mid
    for (tweet in newTweets) {
        uniqueTweets[tweet.mid] = tweet
    }
    
    // Convert back to array and sort by timestamp in descending order
    this.clear()
    this.addAll(uniqueTweets.values.sortedByDescending { it.timestamp })
    Timber.d("[TweetListView] After merge: ${this.size} tweets")
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