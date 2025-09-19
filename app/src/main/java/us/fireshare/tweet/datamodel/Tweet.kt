package us.fireshare.tweet.datamodel

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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

    // the following five attributes are for display only. Not stored in database.
    var author: User? = null,

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
                val gson = GsonBuilder()
                    .registerTypeAdapter(MediaType::class.java, MediaTypeDeserializer())
                    .create()
                
                // Pre-process the dictionary to handle scientific notation in numeric fields
                val processedDict = dict.toMutableMap()
                
                // Validate required fields before processing
                val mid = processedDict["mid"] as? String
                val authorId = processedDict["authorId"] as? String
                
                if (mid.isNullOrBlank() || authorId.isNullOrBlank()) {
                    Timber.e("Tweet.from() - Missing required fields: mid=$mid, authorId=$authorId, dict=$dict")
                    throw IllegalArgumentException("Tweet missing required fields: mid=$mid, authorId=$authorId")
                }
                
                // Handle timestamp field
                processedDict["timestamp"]?.let { value ->
                    when (value) {
                        is Number -> processedDict["timestamp"] = value.toLong()
                        is String -> {
                            try {
                                processedDict["timestamp"] = value.toDouble().toLong()
                            } catch (e: NumberFormatException) {
                                Timber.w("Failed to parse timestamp: $value")
                            }
                        }
                    }
                }
                
                // Handle attachments timestamps if present
                val attachments = processedDict["attachments"] as? List<*>
                if (attachments != null) {
                    val processedAttachments = attachments.map { attachment ->
                        if (attachment is Map<*, *>) {
                            val attachmentMap = attachment.toMutableMap()
                            attachmentMap["timestamp"]?.let { value ->
                                when (value) {
                                    is Number -> attachmentMap["timestamp"] = value.toLong()
                                    is String -> {
                                        try {
                                            attachmentMap["timestamp"] = value.toDouble().toLong()
                                        } catch (e: NumberFormatException) {
                                            Timber.w("Failed to parse attachment timestamp: $value")
                                        }
                                    }
                                }
                            }
                            attachmentMap
                        } else {
                            attachment
                        }
                    }
                    processedDict["attachments"] = processedAttachments
                }
                
                val jsonString = gson.toJson(processedDict)
                val tweet = gson.fromJson(jsonString, Tweet::class.java)

                return getInstance(
                    mid = tweet.mid,
                    authorId = tweet.authorId,
                    content = tweet.content,
                    timestamp = tweet.timestamp,
                    title = tweet.title,
                    originalTweetId = tweet.originalTweetId,
                    originalAuthorId = tweet.originalAuthorId,
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

    var hasRetweeted: Boolean
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
        // Update all properties except author and originalTweet
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
            
            // Pre-process the dictionary to handle scientific notation in numeric fields
            val processedDict = dict.toMutableMap()
            
            // Handle timestamp field
            processedDict["timestamp"]?.let { value ->
                when (value) {
                    is Number -> processedDict["timestamp"] = value.toLong()
                    is String -> {
                        try {
                            processedDict["timestamp"] = value.toDouble().toLong()
                        } catch (e: NumberFormatException) {
                            Timber.w("Failed to parse timestamp: $value")
                        }
                    }
                }
            }
            
            // Handle attachments timestamps and type conversion if present
            val attachments = processedDict["attachments"] as? List<*>
            if (attachments != null) {
                Timber.d("Processing ${attachments.size} attachments")
                val processedAttachments = attachments.map { attachment ->
                    if (attachment is Map<*, *>) {
                        Timber.d("Processing attachment: $attachment")
                        val attachmentMap = attachment.toMutableMap()
                        
                        // Handle timestamp conversion
                        attachmentMap["timestamp"]?.let { value ->
                            when (value) {
                                is Number -> attachmentMap["timestamp"] = value.toLong()
                                is String -> {
                                    try {
                                        attachmentMap["timestamp"] = value.toDouble().toLong()
                                    } catch (e: NumberFormatException) {
                                        Timber.w("Failed to parse attachment timestamp: $value")
                                    }
                                }
                            }
                        }
                        
                        Timber.d("Final processed attachment: $attachmentMap")
                        attachmentMap
                    } else {
                        attachment
                    }
                }
                processedDict["attachments"] = processedAttachments
            }
            
            val jsonElement: JsonElement = gson.toJsonTree(processedDict)
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
}

@Serializable
enum class MediaType {
    Image, Video, HLS_VIDEO, Audio, PDF, Word, Excel, PPT, Zip, Txt, Html, Unknown
}

/**
 * Extension function for merging tweets into an array
 */
fun MutableList<Tweet>.mergeTweets(newTweets: List<Tweet>) {
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
    const val MAX_FILE_SIZE = 120 * 1024 * 1024  // 120MB in bytes - max file size for attachments
    const val CLOUD_PORT = 8010         // port to netdisk and transcode service.
    const val PAGE_SIZE = 10
    const val USER_BATCH_SIZE = 20      // Batch size for user fetching
}

object UserActions {
    const val FAVORITE = 0
    const val BOOKMARK = 1
    const val RETWEET = 2
}
enum class UserContentType(val value: String) {
    FAVORITES("favorite_list"),
    BOOKMARKS("bookmark_list"),
    COMMENTS("comment_list"),
    FOLLOWER("get_followers_sorted"),      // follower list of an user
    FOLLOWING("get_followings_sorted")    // following list
}

/**
 * When tweet is added or removed from the tweet feed list,
 * update tweet list in ProfileScreen, also add or remove it.
 * */

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