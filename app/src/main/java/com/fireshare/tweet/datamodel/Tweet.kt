package com.fireshare.tweet.datamodel

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.fireshare.tweet.widget.MediaType
import kotlinx.serialization.Serializable
import java.util.Date

typealias MimeiId = String      // 27 or 64 character long string

// some application wise constants
object TW_CONST {
    const val GUEST_ID = "000000000000000000000000000"      // 27
    const val CHUNK_SIZE = 1 * 1024 * 1024 // 5MB in bytes
}

object UserFavorites {
    const val LIKE_TWEET = 0
    const val BOOKMARK = 1
    const val RETWEET = 2
}

/**
 * When tweet is added or removed from the tweet feed list,
 * update tweet list in UserProfileScreen, also add or remove it.
 * */
interface TweetActionListener {
    fun onTweetAdded(tweet: Tweet)
    fun onTweetDeleted(tweetId: MimeiId)
}

@Serializable
data class MimeiFileType(
    val mid: MimeiId,
    val type: MediaType,
    val size: Long? = null,
)

@Serializable
@Entity(tableName = "tweets")
data class Tweet(
    @PrimaryKey var mid: MimeiId,
    val authorId: MimeiId,        // mid of the author, is also the mimei database Id
    var content: String? = null,   // content or attachments must have one.
    val timestamp: Long = System.currentTimeMillis(),

    var originalTweetId: MimeiId? = null, // this is retweet id of the original tweet
    var originalAuthorId: MimeiId? = null,  // authorId of the forwarded tweet

    // the following six attributes are for display only. Not stored in database.
    var author: User? = null,
    var originalTweet: Tweet? = null,        // the original tweet for display only.

    // if the current user has liked or bookmarked this tweet
    var favorites: MutableList<Boolean>? = mutableListOf(false, false, false),

    var likeCount: Int = 0,     // Number of likes

    var bookmarkCount: Int = 0, // Number of bookmarks

    // List of retweets ID, without comments.
    var retweetCount: Int = 0,  // Number of retweets

    // List of comments (tweets) Id on this tweet.
    var commentCount: Int = 0,  // Number of comments

    // List of media IDs attached to the tweet. Max 4 items for now.
    var attachments: List<MimeiFileType>? = null,

    var isPrivate: Boolean = false,     // Viewable by the author only if true.
)

@Serializable
data class User(
    var baseUrl: String? = null,        // most recent url used to access user data
    var mid: MimeiId,                   // Unique identifier for the user, and the mimei database
    var name: String? = null,
    var username: String? = null,
    var password: String? = null,       // hashed password
    var avatar: MimeiId? = null,        // Optional profile image URL
    var email: String? = null,
    var profile: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var tweetCount: Int = 0,

    // List of nodes authorized to the user to write tweets on.
    var nodeIds: List<MimeiId>? = null,
    var publicKey: String? = null,

    // List of tweet MIDs bookmarked by the user
    var fansList: List<MimeiId>? = null,
    var followingList: List<MimeiId>? = null,
    var bookmarkedTweets: List<MimeiId>? = null,
    var likedTweets: List<MimeiId>? = null,
    var repliedTweets: List<MimeiId>? = null,

    // List of top tweets liked by the user
    var topTweets: List<MimeiId>? = null,
)

class DateConverter {
    @TypeConverter
    fun fromDate(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun toDate(timestamp: Long?): Date? {
        return timestamp?.let { Date(it) }
    }
}
@Entity
data class CachedTweet(
    @PrimaryKey val mid: MimeiId,
    val originalTweetJson: String? = null, // Store the original tweet as JSON
    val timestamp: Date = Date() // Automatically set to the current date and time
)
@Dao
interface CachedTweetDao {
    @Insert
    fun insertCachedTweet(cachedTweet: CachedTweet)

    @Query("SELECT * FROM CachedTweet WHERE mid = :tweetId")
    fun getCachedTweet(tweetId: String): CachedTweet?

    @Query("DELETE FROM CachedTweet WHERE timestamp < :oneMonthAgo")
    fun deleteOldCachedTweets(oneMonthAgo: Date)

    @Query("DELETE FROM CachedTweet")
    fun clearAllCachedTweets()
}

@Database(entities = [CachedTweet::class], version = 1)
@TypeConverters(DateConverter::class)
abstract class TweetCacheDatabase : RoomDatabase() {
    abstract fun tweetDao(): CachedTweetDao

    companion object {
        @Volatile
        private var INSTANCE: TweetCacheDatabase? = null

        fun getInstance(context: Context): TweetCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TweetCacheDatabase::class.java,
                    "tweet_cache_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
