package us.fireshare.tweet.datamodel

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import java.util.Date

// cache for tweets
@Entity(
    primaryKeys = ["mid", "uid"],
    indices = [Index(value = ["uid"]), Index(value = ["mid"]), Index(value = ["tweetTimestamp"])]
)
data class CachedTweet(
    val mid: MimeiId,   // Tweet's mimei Id
    val uid: MimeiId,       // cache list key
    val originalTweet: Tweet,   // tweet object.
    val timestamp: Date = Date(), // Cache timestamp (when saved to database)
    val tweetTimestamp: Long = 0L  // Tweet's actual creation timestamp (for ordering)
)

@Entity
data class CachedUser(
    @PrimaryKey val userId: MimeiId = appUser.mid,
    val user: User,
    val timestamp: Date = Date() // Timestamp when the user was cached
)

class UserConverter {
    private val gson = Gson().newBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .create()
    
    @TypeConverter
    fun fromUser(user: User): String {
        return gson.toJson(user)
    }

    @TypeConverter
    fun toUser(str: String): User? {
        return try {
            gson.fromJson(str, object : TypeToken<User?>() {}.type)
        } catch (e: Exception) {
            Timber.tag("toUser").e("$e")
            null
        }
    }
}

class TweetConverter {
    private val gson = Gson().newBuilder()
        // Don't use excludeFieldsWithoutExposeAnnotation() because Tweet class doesn't use @Expose
        .create()
    
    @TypeConverter
    fun fromTweet(tweet: Tweet): String {
        return gson.toJson(tweet)
    }

    @TypeConverter
    fun toTweet(str: String): Tweet? {
        return try {
            gson.fromJson(str, object : TypeToken<Tweet?>() {}.type)
        } catch (e: Exception) {
            Timber.tag("toTweet").e("Error deserializing tweet: $e")
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

@Dao
interface CachedTweetDao {
    /**
     * Cache of User list.
     * */
    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy to overwrite existing data
    fun insertOrUpdateCachedUser(cachedUser: CachedUser)

    @Query("SELECT * FROM CachedUser WHERE userId = :userId")
    fun getCachedUser(userId: MimeiId): CachedUser?

    @Query("DELETE FROM CachedUser WHERE userId = :userId")
    fun deleteCachedUser(userId: MimeiId)

    @Query("DELETE FROM CachedUser")
    fun clearAllCachedUsers()

    @Query("SELECT * FROM CachedUser")
    fun getAllCachedUsers(): List<CachedUser>
    
    @Query("DELETE FROM CachedUser WHERE timestamp < :cutoffTime")
    fun deleteOldCachedUsers(cutoffTime: Date)

    /**
     * Cache of tweets. Clear tweets cached more than a month ago with Cleanup workerManager.
     * */
    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy
    fun insertOrUpdateCachedTweet(cachedTweet: CachedTweet)

    /**
     * Get a cached tweet by tweet ID (mid).
     * 
     * IMPORTANT: Searches across ALL user caches, not filtered by uid.
     * This is necessary because tweets can be cached under different uid values:
     * - Original tweets: cached by authorId
     * - Main feed tweets: cached by main_feed_<appUser.mid>
     * - Profile tweets: cached by profile user mid
     * - Bookmarks/favorites: cached by their list keys
     * 
     * When we only have a tweet mid, we don't know which cache it's in, so we search all caches.
     */
    @Query("SELECT * FROM CachedTweet WHERE mid = :tweetId ORDER BY timestamp DESC LIMIT 1")
    fun getCachedTweet(tweetId: MimeiId): CachedTweet?

    @Query("SELECT * FROM CachedTweet WHERE mid = :tweetId ORDER BY timestamp DESC")
    fun getCachedTweetCopies(tweetId: MimeiId): List<CachedTweet>

    @Query("UPDATE CachedTweet SET originalTweet = :tweet, tweetTimestamp = :tweetTimestamp WHERE mid = :tweetId")
    fun updateCachedTweetPayload(tweetId: MimeiId, tweet: Tweet, tweetTimestamp: Long)

    @Query("SELECT * FROM CachedTweet ORDER BY tweetTimestamp DESC LIMIT :count OFFSET :offset")
    fun getCachedTweets(offset: Int, count: Int): List<CachedTweet>

    @Query("SELECT * FROM CachedTweet ORDER BY tweetTimestamp DESC LIMIT :limit")
    fun getRecentCachedTweets(limit: Int): List<CachedTweet>

    /**
     * Get cached tweets by user ID.
     * For bookmarks and favorites, order by cache timestamp to preserve server order
     * (cache timestamp reflects the order they were saved, which matches server order).
     * For regular tweets, order by tweet creation timestamp.
     */
    @Query("SELECT * FROM CachedTweet WHERE uid = :userId " +
            "ORDER BY " +
            "CASE " +
            "  WHEN uid LIKE 'bookmark_list_%' THEN timestamp " +
            "  WHEN uid LIKE 'favorite_list_%' THEN timestamp " +
            "  ELSE tweetTimestamp " +
            "END DESC " +
            "LIMIT :count OFFSET :offset")
    fun getCachedTweetsByUser(userId: MimeiId, offset: Int, count: Int): List<CachedTweet>

    /**
     * Delete expired tweets older than the cutoff date.
     * 
     * IMPORTANT: Never-expiring tweets:
     * - Bookmarks (uid LIKE 'bookmark_list_%') - User's saved tweets
     * - Favorites (uid LIKE 'favorite_list_%') - User's favorited tweets
     * - AppUser's private tweets - Checked in CleanUpWorker code
     * 
     * Expiration applies to other caches:
     * - Tweets cached by authorId (public tweets from profiles)
     * - Tweets cached by main_feed_<appUser.mid> (main feed public tweets)
     * 
     * Expiration is based on timestamp, excluding special cache buckets.
     */
    @Query("DELETE FROM CachedTweet WHERE timestamp < :oneMonthAgo AND uid NOT LIKE 'bookmark_list_%' AND uid NOT LIKE 'favorite_list_%'")
    fun deleteOldCachedTweets(oneMonthAgo: Date)
    
    /**
     * Get old cached tweets that would be deleted (for filtering private tweets)
     */
    @Query("SELECT * FROM CachedTweet WHERE timestamp < :oneMonthAgo AND uid NOT LIKE 'bookmark_list_%' AND uid NOT LIKE 'favorite_list_%'")
    fun getOldCachedTweets(oneMonthAgo: Date): List<CachedTweet>

    @Query("DELETE FROM CachedTweet")
    fun clearAllCachedTweets()

    @Query("DELETE FROM CachedTweet WHERE mid = :tweetId")
    fun deleteCachedTweet(tweetId: MimeiId)

    @Query("DELETE FROM CachedTweet WHERE mid = :tweetId AND uid = :cacheKey")
    fun deleteCachedTweetFromCache(tweetId: MimeiId, cacheKey: MimeiId)
}

@Database(entities = [CachedTweet::class, CachedUser::class, BlacklistEntry::class], version = 14, exportSchema = false)
@TypeConverters(DateConverter::class, TweetConverter::class, UserConverter::class)
abstract class TweetCacheDatabase : RoomDatabase() {
    abstract fun tweetDao(): CachedTweetDao
    abstract fun blacklistDao(): BlacklistDao

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
                    .fallbackToDestructiveMigration(false)
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add timestamp column to CachedUser table
                db.execSQL("ALTER TABLE CachedUser ADD COLUMN timestamp INTEGER DEFAULT ${System.currentTimeMillis()}")
            }
        }
        
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Clear corrupted tweet cache (tweets were serialized incorrectly with excludeFieldsWithoutExposeAnnotation)
                Timber.tag("TweetCacheDatabase").w("Migration 11->12: Clearing corrupted tweet cache")
                db.execSQL("DELETE FROM CachedTweet")
            }
        }
        
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add tweetTimestamp column to CachedTweet table for proper ordering
                Timber.tag("TweetCacheDatabase").w("Migration 12->13: Adding tweetTimestamp column")
                db.execSQL("ALTER TABLE CachedTweet ADD COLUMN tweetTimestamp INTEGER NOT NULL DEFAULT 0")
                // Create index for faster ordering
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CachedTweet_tweetTimestamp ON CachedTweet(tweetTimestamp)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.tag("TweetCacheDatabase").w("Migration 13->14: Re-keying tweet cache by tweet/list membership")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS CachedTweet_new (
                        mid TEXT NOT NULL,
                        uid TEXT NOT NULL,
                        originalTweet TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        tweetTimestamp INTEGER NOT NULL,
                        PRIMARY KEY(mid, uid)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO CachedTweet_new (mid, uid, originalTweet, timestamp, tweetTimestamp)
                    SELECT mid, uid, originalTweet, timestamp, tweetTimestamp FROM CachedTweet
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE CachedTweet")
                db.execSQL("ALTER TABLE CachedTweet_new RENAME TO CachedTweet")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CachedTweet_uid ON CachedTweet(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CachedTweet_mid ON CachedTweet(mid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_CachedTweet_tweetTimestamp ON CachedTweet(tweetTimestamp)")
            }
        }
    }
}
