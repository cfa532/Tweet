package com.fireshare.tweet.datamodel

import android.content.Context
import android.os.Parcelable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.Date


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

object UserFavorites {
    const val LIKE_TWEET = 0
    const val BOOKMARK = 1
    const val RETWEET = 2
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

    var likeCount: Int = 0,     // Number of likes

    var bookmarkCount: Int = 0, // Number of bookmarks

    // List of retweets ID, without comments.
    var retweetCount: Int = 0,  // Number of retweets

    // List of comments (tweets) Id on this tweet.
    var commentCount: Int = 0,  // Number of comments

    // List of media IDs attached to the tweet
    var attachments: List<MimeiFileType>? = null,

    var isPrivate: Boolean = false,     // Viewable by the author only if true.
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

    var tweetCount: Int = 0,
    var followingCount: Int? = null,
    var followersCount: Int? = null,
    var bookmarksCount: Int? = null,
    var favoritesCount: Int? = null,
    var commentsCount: Int? = null,

    var hostIds: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable
    var publicKey: String? = null,

    var fansList: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable
    var followingList: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable
    var bookmarkedTweets: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable
    var likedTweets: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable
    var repliedTweets: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable
    var commentsList: List<MimeiId>? = null,  // Ensure MimeiId is Parcelable

    var topTweets: List<MimeiId>? = null  // Ensure MimeiId is Parcelable
) : Parcelable

/**
 * IP address of the first node in HostIds, which the user is authorized to write data on.
 * */
suspend fun User.writableUrl(): String? {
    return if (!writableUrl.isNullOrEmpty()) { // Check for null or empty string
        this.baseUrl = writableUrl
        writableUrl
    } else {
        hostIds?.firstOrNull()?.let { hostId ->
            HproseInstance.getHostIP(hostId)?.let { hostIP ->
                "http://$hostIP".also { newUrl ->
                    this.writableUrl = newUrl
                    this.baseUrl = newUrl
                }
            } ?: baseUrl
        }
    }
}
// Do Not update baseUrl, used when no need to sync written data
suspend fun User.writableUrl2(): String? {
    return if (!writableUrl.isNullOrEmpty()) { // Check for null or empty string
        writableUrl
    } else {
        hostIds?.firstOrNull()?.let { hostId ->
            HproseInstance.getHostIP(hostId)?.let { hostIP ->
                "http://$hostIP".also { newUrl ->
                    this.writableUrl = newUrl
                }
            } ?: baseUrl
        }
    }
}

// cache for tweets
@Entity
data class CachedTweet(
    @PrimaryKey val mid: MimeiId,
    val originalTweetJson: String? = null, // Store the original tweet as JSON
    val timestamp: Date = Date() // Automatically set to the current date and time
)

// cache of appUser's followings list
@Entity
data class UserData(
    @PrimaryKey val userId: MimeiId = appUser.mid,
    val followings: List<MimeiId> = emptyList()
)
@Entity
data class TweetMidList(
    @PrimaryKey val userId: String,
    val tweetMidList: List<MimeiId> = emptyList()
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

class MimeiIdListConverter {
    @TypeConverter
    fun fromMimeiIdList(list: List<MimeiId>?): String? {
        return list?.joinToString(",")
    }

    @TypeConverter
    fun toMimeiIdList(str: String?): List<MimeiId>? {
        val type = object : TypeToken<List<MimeiId>>() {}.type
        return Gson().fromJson(str, type)
    }
}

@Dao
interface CachedTweetDao {
    /**
     * Cache of appUser's followings list.
     * */
    @Query("SELECT followings FROM UserData WHERE userId = :userId")
    suspend fun getCachedFollowings(userId: MimeiId = appUser.mid): List<MimeiId>?

    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy to overwrite existing data
    suspend fun insertOrUpdateUserData(userData: UserData)

    /**
     * Cache of tweet's mid list of a given user.
     * */
    @Query("SELECT tweetMidList FROM TweetMidList WHERE userId = :userId")
    suspend fun getCachedTweetMidList(userId: MimeiId): List<MimeiId>?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTweetMidList(tweetMidList: TweetMidList)

    /**
     * Cache of tweets. Clear tweets cached more than a month ago with Cleanup workerManager.
     * */
    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy
    fun insertOrUpdateCachedTweet(cachedTweet: CachedTweet)

    @Query("SELECT * FROM CachedTweet WHERE mid = :tweetId")
    fun getCachedTweet(tweetId: MimeiId): CachedTweet?

    @Query("DELETE FROM CachedTweet WHERE timestamp < :oneMonthAgo")
    fun deleteOldCachedTweets(oneMonthAgo: Date)

    @Query("DELETE FROM CachedTweet")
    fun clearAllCachedTweets()

    @Update
    fun updateCachedTweet(cachedTweet: CachedTweet)

    @Query("DELETE FROM CachedTweet WHERE mid = :tweetId")
    fun deleteCachedTweet(tweetId: MimeiId)

    @Transaction
    suspend fun deleteCachedTweetAndRemoveFromMidList(
        tweetId: MimeiId,
        authorId: MimeiId = appUser.mid
    ) {
        // 1. Delete the CachedTweet
        deleteCachedTweet(tweetId)

        // 2. Remove the tweetId from TweetMidList
        val tweetMidList = getCachedTweetMidList(authorId) // Assuming appUser.mid is the userId
        if (tweetMidList != null) {
            val updatedMidList = tweetMidList.toMutableList().apply { remove(tweetId) }
            insertOrUpdateTweetMidList(TweetMidList(authorId, updatedMidList))
        }
    }
}

@Database(entities = [CachedTweet::class, UserData::class, TweetMidList::class], version = 2)
@TypeConverters(DateConverter::class, MimeiIdListConverter::class)
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
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
