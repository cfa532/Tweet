package com.fireshare.tweet.datamodel

import android.content.Context
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
import com.fireshare.tweet.HproseInstance.appUser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import java.util.Date

// cache for tweets
@Entity
data class CachedTweet(
    @PrimaryKey val mid: MimeiId,   // Tweet's mimei Id
    val uid: MimeiId,       // user Id
    val originalTweet: Tweet, // Store the original tweet as JSON
    val timestamp: Date = Date() // Automatically set to the current date and time
)

// cache of appUser's followings list
@Entity
data class CachedUser(
    @PrimaryKey val userId: MimeiId = appUser.mid,
    val followings: List<MimeiId> = emptyList()
)
@Entity
data class TweetMidList(
    @PrimaryKey val userId: String,
    val tweetMidList: List<MimeiId> = emptyList()
)

class UserConverter {
    @TypeConverter
    fun fromUser(user: User): String {
        return Gson().toJson(user)
    }

    @TypeConverter
    fun toUser(str: String): User? {
        return try {
            Gson().fromJson(str, object : TypeToken<User?>() {}.type)
        } catch (e: Exception) {
            Timber.tag("toUser").e("$e")
            null
        }
    }
}

class TweetConverter {
    @TypeConverter
    fun fromTweet(tweet: Tweet): String {
        return Gson().toJson(tweet)
    }

    @TypeConverter
    fun toTweet(str: String): Tweet? {
        return try {
            Gson().fromJson(str, object : TypeToken<Tweet?>() {}.type)
        } catch (e: Exception) {
            Timber.tag("toTweet").e("$e")
            null
        }
    }
}

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
    @Query("SELECT followings FROM CachedUser WHERE userId = :userId")
    suspend fun getCachedFollowings(userId: MimeiId = appUser.mid): List<MimeiId>?

    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy to overwrite existing data
    suspend fun insertOrUpdateUserData(cachedUser: CachedUser)

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

    @Query("SELECT * FROM CachedTweet WHERE timestamp BETWEEN :endTime AND :startTime" +
            " ORDER BY timestamp DESC")
    fun getCachedTweets(startTime: Long, endTime: Long): List<CachedTweet>

    @Query("SELECT * FROM CachedTweet WHERE uid = :userId ORDER BY timestamp DESC" +
            " LIMIT :limit OFFSET :offset")
    fun getCachedTweetsByUser(userId: MimeiId, limit: Int, offset: Int): List<CachedTweet>

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

@Database(entities = [CachedTweet::class, CachedUser::class, TweetMidList::class], version = 5)
@TypeConverters(DateConverter::class, MimeiIdListConverter::class,
    TweetConverter::class, UserConverter::class)
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
